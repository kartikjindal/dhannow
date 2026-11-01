package com.navix.loan.service;

import com.navix.common.collections.SettlementDirectory;
import com.navix.common.idempotency.IdempotencyService;
import com.navix.common.security.ActorContext;
import com.navix.common.security.SeparationOfDutiesGuard;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.event.RepaymentRecordedEvent;
import com.navix.common.notification.event.RepaymentRejectedEvent;
import com.navix.common.notification.event.RepaymentVerifiedEvent;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.domain.PaymentPurpose;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.domain.RepaymentStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.dto.LoanDtos.PaymentView;
import com.navix.loan.repository.PaymentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and verifies repayments against a loan (integer paise).
 *
 * <p>Allocation is simple at the ledger level: the stored {@code loan.outstanding} is
 * {@code totalRepayable − Σ verified payments}. Proof is required before a payment counts, so only
 * VERIFIED payments reduce the balance. Partial payments are allowed; the loan CLOSES at zero.
 * The authoritative, prepayment-aware figure is {@link #outstandingAsOf} (compute-on-read).
 */
@Service
@RequiredArgsConstructor
public class RepaymentService {

    /**
     * Every date in this class is an <em>Indian</em> calendar date. The server clock runs UTC in ECS,
     * so a bare {@code LocalDate.now(IST)} reports yesterday between 00:00 and 05:30 IST — one wrong day
     * of interest, and a wrong DPD/due boundary. Matches {@code OfferService}/{@code DashboardService}.
     */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** How far back a BORROWER may date their own payment. Staff (and ADMIN) are not bound by it. */
    private static final int BORROWER_BACKDATE_DAYS = 3;

    private final PaymentRepository paymentRepository;
    private final LoanRepository loanRepository;
    // Each loan is priced on the version it was disbursed on (V94), never the current one.
    private final PricingService pricing;
    private final ApplicationFlowService applicationFlowService;
    private final SettlementDirectory settlementDirectory;
    private final ApplicationEventPublisher eventPublisher;
    private final DsaCommissionService dsaCommissionService;
    private final DocumentStoragePort storage;
    private final CreditLevelService creditLevels;
    private final PaymentAllocator allocator;
    private final IdempotencyService idempotency;
    private final LoanExtensionLedger extensionLedger;

    /**
     * Record a (possibly partial) repayment. Idempotent on {@code txnRef} per loan.
     *
     * <p>A screenshot is mandatory ONLY when the caller is the borrower themselves — staff recording
     * on a borrower's behalf (a branch walk-in, a phoned-in reference) legitimately has no upload, and
     * collections' validated-payment path (proof is deliberately flexible there — screenshot / text /
     * transaction id, revamp.md decision) also flows through this same method. Gate on the actor, not
     * a DTO-level constraint, so those two paths stay unaffected.
     */
    @Transactional
    public Payment recordPayment(Long loanId, long amountPaise, PaymentMethod method,
                                 String txnRef, String proofUrl, LocalDate paidOn) {
        boolean staff = !"BORROWER".equals(ActorContext.get().role());
        return recordPayment(loanId, amountPaise, method, txnRef, proofUrl, paidOn,
                staff ? staffIdOrNull() : null, true);
    }

    /**
     * {@link #recordPayment(Long, long, PaymentMethod, String, String, LocalDate)} behind an
     * {@code Idempotency-Key} (CC-22): a double tap or a network retry with the same key returns the first
     * payment. The borrower's app must send one; staff may.
     */
    @Transactional
    public Payment submitPayment(String idempotencyKey, Long loanId, long amountPaise, PaymentMethod method,
                                 String txnRef, String proofUrl, LocalDate paidOn) {
        return submitPayment(idempotencyKey, loanId, amountPaise, method, txnRef, proofUrl, paidOn,
                PaymentPurpose.REPAYMENT, null);
    }

    /**
     * As above for either purpose. {@code EXTENSION} pays the borrower's ACCEPTED extension
     * {@code extensionId}, for exactly its quote on {@code paidOn} (PLAN Phase 6b).
     */
    @Transactional
    public Payment submitPayment(String idempotencyKey, Long loanId, long amountPaise, PaymentMethod method,
                                 String txnRef, String proofUrl, LocalDate paidOn, PaymentPurpose purpose,
                                 java.util.UUID extensionId) {
        var actor = ActorContext.get();
        PaymentPurpose p = purpose != null ? purpose : PaymentPurpose.REPAYMENT;
        Long recorder = "BORROWER".equals(actor.role()) ? null : staffIdOrNull();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            if ("BORROWER".equals(actor.role())) {
                throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key with this request.");
            }
            return recordPayment(loanId, amountPaise, method, txnRef, proofUrl, paidOn, recorder, true, p, extensionId);
        }
        String request = String.join("|", actor.id(), String.valueOf(loanId), String.valueOf(amountPaise),
                String.valueOf(method), String.valueOf(normaliseUtr(txnRef)), String.valueOf(proofUrl),
                String.valueOf(paidOn)) + (p == PaymentPurpose.EXTENSION ? "|EXTENSION|" + extensionId : "");
        Long id = idempotency.execute("REPAYMENT_SUBMIT", idempotencyKey, request,
                () -> recordPayment(loanId, amountPaise, method, txnRef, proofUrl, paidOn, recorder, true, p,
                        extensionId).getId());
        return paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", String.valueOf(id)));
    }

    /**
     * A payment reported by a gateway webhook (flag {@code payment-gateway}, OFF in v1). It waits for an
     * Accountant like any other; the caller guards it with the provider's event id.
     */
    @Transactional
    public Payment recordGatewayPayment(Long loanId, long amountPaise, String utr, LocalDate paidOn) {
        return recordPayment(loanId, amountPaise, PaymentMethod.UPI, utr, null, paidOn, null, false);
    }

    /**
     * As above, with the recorder named. {@code recordedBy} null = no staff maker to separate from: the
     * borrower, or a payment already validated upstream ({@code LoanDirectoryAdapter.creditCollectionPayment}).
     */
    @Transactional
    Payment recordPayment(Long loanId, long amountPaise, PaymentMethod method,
                          String txnRef, String proofUrl, LocalDate paidOn, Long recordedBy) {
        return recordPayment(loanId, amountPaise, method, txnRef, proofUrl, paidOn, recordedBy, false);
    }

    /**
     * {@code strictUtr}: a UTR already on file is refused (CC-07). The upstream-validated paths (collections,
     * NACH, gateway) pass false, so a reference already recorded on the same loan returns that payment and
     * the same money is never counted twice; on another loan it is still refused.
     */
    private Payment recordPayment(Long loanId, long amountPaise, PaymentMethod method, String txnRef,
                                  String proofUrl, LocalDate paidOn, Long recordedBy, boolean strictUtr) {
        return recordPayment(loanId, amountPaise, method, txnRef, proofUrl, paidOn, recordedBy, strictUtr,
                PaymentPurpose.REPAYMENT, null);
    }

    private Payment recordPayment(Long loanId, long amountPaise, PaymentMethod method, String txnRef,
                                  String proofUrl, LocalDate paidOn, Long recordedBy, boolean strictUtr,
                                  PaymentPurpose purpose, java.util.UUID extensionId) {
        Loan loan = requireLoan(loanId);
        requireOwnLoanIfBorrower(loan);
        if (loan.getLifecycleStatus() == LoanLifecycle.DISBURSEMENT_PENDING) {
            throw new BusinessException("LOAN_NOT_ACTIVE",
                    "This loan starts once we confirm the money reached your account.");
        }
        if ("BORROWER".equals(ActorContext.get().role()) && (proofUrl == null || proofUrl.isBlank())) {
            throw new BusinessException("PAYMENT_PROOF_REQUIRED",
                    "Attach a screenshot of your payment before submitting.");
        }
        if (loan.getStatus() == LoanStatus.CLOSED || loan.getStatus() == LoanStatus.REPAID) {
            throw new BusinessException("LOAN_SETTLED", "Loan is already settled");
        }
        if (amountPaise <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Payment amount must be positive");
        }
        String utr = normaliseUtr(txnRef);
        if (utr != null) {
            var existing = paymentRepository.findFirstByTxnRefAndStatusNot(utr, PaymentStatus.REJECTED);
            if (existing.isPresent()) {
                // One transfer is a repayment or an extension charge, never both (PLAN Phase 6b).
                if (existing.get().getPurpose() != purpose) {
                    throw new BusinessException("UTR_USED_FOR_OTHER_PURPOSE",
                            "This payment reference is already on file for "
                                    + (existing.get().isExtension() ? "an extension." : "a repayment."));
                }
                if (!strictUtr && existing.get().getLoanId().equals(loanId)) {
                    return existing.get();
                }
                throw duplicateUtr();
            }
        }
        LocalDate effectivePaidOn = requireSanePaidOn(loan, paidOn);
        boolean extension = purpose == PaymentPurpose.EXTENSION;
        if (extension) {
            extensionLedger.requirePayable(loan, extensionId, amountPaise, effectivePaidOn);
        }
        // Penalty-aware remaining as of the payment date, so a short payment on an overdue loan is
        // correctly flagged partial (the stored total excludes the accruing late penalty). Not for an
        // extension charge, which pays no part of the balance.
        long remaining = extension ? 0L : outstandingAsOf(loanId, effectivePaidOn);

        Payment payment = new Payment();
        payment.setLoanId(loanId);
        payment.setAmount(amountPaise);
        payment.setMethod(method);
        payment.setStatus(PaymentStatus.PENDING_VERIFICATION);
        payment.setTxnRef(utr);
        payment.setProofUrl(proofUrl);
        payment.setPaidOn(effectivePaidOn);
        payment.setPartial(!extension && amountPaise < remaining);
        payment.setRecordedBy(recordedBy);
        payment.setPurpose(purpose);
        payment.setExtensionId(extension ? extensionId : null);
        Payment saved;
        try {
            saved = paymentRepository.saveAndFlush(payment);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two submits of one UTR at the same moment (V101), or a second payment for one extension (V103).
            throw duplicateUtr();
        }
        if (extension) {
            extensionLedger.markPaymentPending(extensionId);
        } else {
            // The loan is managed in this transaction, so the change is flushed with it.
            loan.setRepaymentStatus(RepaymentStatus.PAID_PENDING_VERIFICATION);
        }
        eventPublisher.publishEvent(new RepaymentRecordedEvent(
                loanId, loan.getCustomerId(), saved.getId(), amountPaise, Instant.now()));
        return saved;
    }

    /**
     * The payment date decides how much interest was owed (see {@link #recomputeOutstanding}), so it
     * is a money input and validated like one — it was previously accepted unchecked from every
     * caller, including the borrower's own app.
     *
     * <p>Two rules bind everybody: a payment cannot be made in the future, and it cannot predate the
     * disbursal that created the debt. On top of that a BORROWER may only date a payment within the
     * last few days — self-service backdating is how an overdue borrower would erase their own
     * penalty. Staff backdate legitimately (a branch walk-in, a reconciled bank line, collections'
     * validated payments), so the window is borrower-only, not a blanket rule.
     */
    /** UTRs are compared without spaces and case (V101 stores them this way); blank is none. */
    static String normaliseUtr(String txnRef) {
        if (txnRef == null) {
            return null;
        }
        String utr = txnRef.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
        return utr.isEmpty() ? null : utr;
    }

    private static BusinessException duplicateUtr() {
        return new BusinessException("DUPLICATE_UTR", "This payment reference is already on file.");
    }

    private LocalDate requireSanePaidOn(Loan loan, LocalDate paidOn) {
        LocalDate today = LocalDate.now(IST);
        LocalDate effective = paidOn != null ? paidOn : today;
        // One day of slack, not zero: the borrower's app sends its own LOCAL date, so a handset in a
        // timezone ahead of IST (or one with a skewed clock) legitimately posts tomorrow's date for
        // a payment made right now. Rejecting on the nose would turn that into an unexplainable
        // failure on a real payment; a day beyond that is not a clock, it is a claim.
        if (effective.isAfter(today.plusDays(1))) {
            throw new BusinessException("INVALID_PAID_ON", "Payment date cannot be in the future");
        }
        // Never let a future date drive the interest arithmetic, whatever the handset said.
        if (effective.isAfter(today)) {
            effective = today;
        }
        if (loan.getDisbursedOn() != null && effective.isBefore(loan.getDisbursedOn())) {
            throw new BusinessException("INVALID_PAID_ON",
                    "Payment date cannot be before the loan was disbursed");
        }
        if ("BORROWER".equals(ActorContext.get().role())
                && effective.isBefore(today.minusDays(BORROWER_BACKDATE_DAYS))) {
            throw new BusinessException("INVALID_PAID_ON",
                    "Payment date is too far in the past — contact support to record an older payment.");
        }
        return effective;
    }

    /** Confirm proof for a payment; recomputes the loan balance and closes it at zero. */
    @Transactional
    public Payment verifyPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", String.valueOf(paymentId)));
        if (payment.getStatus() == PaymentStatus.REJECTED) {
            throw new BusinessException("PAYMENT_REJECTED", "A rejected payment cannot be verified; record it again.");
        }
        Loan loan = requireLoan(payment.getLoanId());
        if (payment.getStatus() != PaymentStatus.VERIFIED) {
            // PLAN Phase 4: whoever recorded a payment never verifies it, ADMIN included.
            if (payment.getRecordedBy() != null) {
                SeparationOfDutiesGuard.paymentVerifier(staffIdOrNull(), payment.getRecordedBy());
            }
            payment.setStatus(PaymentStatus.VERIFIED);
            stampDecider(payment);
            paymentRepository.save(payment);
            // An extension charge moves the due date instead of reducing the balance (PLAN Phase 6b). If the
            // extension was cancelled meanwhile (the loan closed), all of it is refunded (CC-69).
            if (!payment.isExtension()) {
                allocate(loan, payment);
            } else if (!extensionLedger.onVerified(loan, payment)) {
                allocator.allocate(payment, 0L, 0L, 0L);
            }
        }
        recomputeOutstanding(payment.getLoanId());
        if (payment.isExtension()) {
            return payment; // the borrower hears EXTENSION_ACTIVATED instead (Phase 6b)
        }
        eventPublisher.publishEvent(new RepaymentVerifiedEvent(
                loan.getId(), loan.getCustomerId(), payment.getId(), payment.getAmount(),
                loan.getStatus() == LoanStatus.CLOSED, Instant.now()));
        return payment;
    }

    /**
     * The waterfall split (CC-09), against what was owed on the payment's bank date before any payment.
     * Money reaching a loan that had already closed owes nothing: all of it is refunded (CC-08).
     */
    private void allocate(Loan loan, Payment payment) {
        if (loan.getLifecycleStatus() == LoanLifecycle.CLOSED) {
            allocator.allocate(payment, 0L, 0L, 0L);
            return;
        }
        LocalDate at = payment.getPaidOn() != null ? payment.getPaidOn() : LocalDate.now(IST);
        OutstandingBreakdown owed = breakdown(loan, at, 0L, null);
        allocator.allocate(payment, owed.penaltyPaise(), owed.interestPaise(), loan.getPrincipal());
    }

    /**
     * Record WHO decided this payment and when (V59) — the Accountant's verify/reject was previously
     * unattributable, which left their core daily work invisible to the staff-performance dashboard.
     * Deliberately not {@code updatedBy}: that holds a mutable display name and any later write to
     * the row overwrites it. Which way the decision went stays on {@code status}.
     */
    private void stampDecider(Payment payment) {
        // No resolvable staff id (system/scheduler path) — left null rather than guessed.
        payment.setDecidedBy(staffIdOrNull());
        payment.setDecidedAt(Instant.now());
    }

    private static Long staffIdOrNull() {
        try {
            return Long.valueOf(ActorContext.get().id());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The fixed rejection-reason picklist — kept here (not a DB enum/check) to match how
     *  {@link PaymentMethod}/{@link PaymentStatus} are already enforced above the DB in this schema. */
    private static final java.util.Set<String> REJECTION_REASONS = java.util.Set.of(
            "WRONG_REFERENCE", "AMOUNT_MISMATCH", "NOT_RECEIVED", "UNREADABLE_PROOF", "OTHER");

    /**
     * Reject a recorded payment (the accountant couldn't match the proof / transfer). Terminal —
     * a rejected payment never counts toward the outstanding (only VERIFIED payments are summed),
     * so no balance recompute is needed. A VERIFIED payment cannot be rejected. {@code reason} is
     * required and must be one of {@link #REJECTION_REASONS}; {@code note} is optional free text.
     * Notifies the borrower (IN_APP/EMAIL carry the reason; the SMS body is DLT-locked and unchanged).
     */
    @Transactional
    public Payment rejectPayment(Long paymentId, String reason, String note) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REJECTION_REASON_REQUIRED", "A rejection reason is required");
        }
        if (!REJECTION_REASONS.contains(reason)) {
            throw new BusinessException("INVALID_REJECTION_REASON",
                    "reason must be one of " + REJECTION_REASONS);
        }
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", String.valueOf(paymentId)));
        if (payment.getStatus() == PaymentStatus.VERIFIED) {
            throw new BusinessException("PAYMENT_ALREADY_VERIFIED", "A verified payment cannot be rejected");
        }
        if (payment.getStatus() != PaymentStatus.REJECTED) {
            payment.setStatus(PaymentStatus.REJECTED);
            payment.setRejectionReason(reason);
            payment.setRejectionNote(note);
            stampDecider(payment);
            paymentRepository.save(payment);
            if (payment.isExtension()) {
                // EXT-19: the borrower may pay again until the pay-by day.
                extensionLedger.markPaymentRejected(payment.getExtensionId());
            }
        }
        Loan loan = requireLoan(payment.getLoanId());
        // No balance change, only where repayment stands; the managed loan is flushed with the payment.
        refreshRepaymentStatus(loan);
        eventPublisher.publishEvent(new RepaymentRejectedEvent(
                loan.getId(), loan.getCustomerId(), payment.getId(), payment.getAmount(),
                reason, note, Instant.now()));
        return payment;
    }

    @Transactional(readOnly = true)
    public List<Payment> listPayments(Long loanId) {
        return paymentRepository.findByLoanId(loanId);
    }

    /** Accountant queue: every repayment still awaiting proof verification, across all loans. */
    @Transactional(readOnly = true)
    public List<Payment> listPending() {
        return paymentRepository.findByStatusOrderByIdAsc(PaymentStatus.PENDING_VERIFICATION);
    }

    /**
     * {@link PaymentView} with {@code proofUrl} resolved to a short-lived presigned GET — the DTO
     * factories only know the raw S3 key, and every read path (borrower's own list, the accountant's
     * verify queue, a staff loan-detail dialog) needs a link the browser can actually open.
     */
    public PaymentView view(Payment p) {
        return PaymentView.ofWithResolvedProof(p, presignedProof(p));
    }

    /** {@link #view(Payment)} + the staff-only borrower context (verify queue). */
    public PaymentView view(Payment p, Long customerId, String customerName) {
        return PaymentView.ofWithResolvedProof(p, customerId, customerName, presignedProof(p));
    }

    private String presignedProof(Payment p) {
        String key = p.getProofUrl();
        return key == null || key.isBlank() ? null : storage.presignDownload(key);
    }

    /**
     * Authoritative outstanding balance at a date. DhanNow (UPFRONT) terms: principal + late charge
     * (penal rate × principal per day past due, capped at the frozen cap) − verified payments; the
     * interest was deducted at disbursal, so it is reported ({@code interestPrepaidPaise}) and never owed
     * (PLAN Phase 10b R4). Navix (ACCRUED) terms add the interest accrued to {@code asOf}.
     *
     * <p><b>Approved settlement:</b> when collections has an approved partial settlement for the loan
     * (via {@link SettlementDirectory}), the agreed full-and-final amount caps the payable. Because a
     * settlement is a concession it can only <i>reduce</i> what the borrower owes — never increase it —
     * so the result is {@code min(formulaOwed, settlement − verified)}. The borrower's repay page,
     * dashboard and the close-at-zero logic all read this method, so an approved settlement becomes the
     * borrower-facing payable and the loan closes once the settled amount is paid.
     */
    @Transactional(readOnly = true)
    public long outstandingAsOf(Long loanId, LocalDate asOf) {
        return outstandingBreakdownAsOf(loanId, asOf).outstandingPaise();
    }

    /**
     * The itemized make-up of {@link #outstandingAsOf}: the same accrued-interest, late-penalty and
     * verified-payment figures the net balance is built from, exposed individually so the UI can show
     * a full cost breakdown without re-deriving (and forking) the math. {@code interestPaise} and
     * {@code penaltyPaise} are the <i>scheduled/accrued</i> amounts as of {@code asOf}; when
     * {@code settledAmountPaise} is non-null the net {@code outstandingPaise} is the settlement-capped
     * full-and-final figure, so the components may then sum to more than the net.
     *
     * <p>{@code interestDays} / {@code penaltyDays} are the <i>day counts</i> those two amounts were
     * charged over — interest days are capped at the tenure (interest stops accruing at the due date;
     * 0 on an UPFRONT loan, whose interest was deducted at disbursal) and penalty days exclude the
     * loan's own {@code graceDays} (1 on legacy loans) and are capped at the version's penal cap. Exposed so collections can show "1%/day × N days"
     * rather than an unexplained rupee figure, without re-deriving (and forking) the math.
     */
    public record OutstandingBreakdown(long outstandingPaise, long interestPaise, long penaltyPaise,
                                       long verifiedPaise, Long settledAmountPaise,
                                       int interestDays, int penaltyDays, long interestPrepaidPaise) {
    }

    @Transactional(readOnly = true)
    public OutstandingBreakdown outstandingBreakdownAsOf(Long loanId, LocalDate asOf) {
        return outstandingBreakdownAsOf(requireLoan(loanId), loanId, asOf);
    }

    /**
     * {@link #outstandingBreakdownAsOf(Long, LocalDate)} for a {@link Loan} the caller already holds —
     * saves the reload, and is otherwise identical (it still issues the verified-payment sum and the
     * settlement lookup itself). For a whole page of loans use {@link #outstandingBreakdownsForAll},
     * which batches those two lookups as well.
     */
    @Transactional(readOnly = true)
    public OutstandingBreakdown outstandingBreakdownAsOf(Loan loan, LocalDate asOf) {
        return outstandingBreakdownAsOf(loan, loan.getId(), asOf);
    }

    /**
     * The id is threaded separately rather than read off the entity so the by-id path keeps asking
     * about exactly the loan the caller named.
     */
    private OutstandingBreakdown outstandingBreakdownAsOf(Loan loan, Long loanId, LocalDate asOf) {
        long verified = paymentRepository.sumAmountByLoanIdAndStatus(loanId, PaymentStatus.VERIFIED);
        Long settled = settlementDirectory.approvedSettlementAmount(loanId).orElse(null);
        return breakdown(loan, asOf, verified, settled);
    }

    /**
     * The <b>one</b> implementation of the outstanding formula, shared by the single-loan and the
     * batched paths so the two can never drift. Pure arithmetic — every input it needs (the loan, the
     * as-of date, the verified-payment sum, the approved settlement or null) is passed in, so it
     * issues no queries of its own.
     */
    private OutstandingBreakdown breakdown(Loan loan, LocalDate asOf, long verified, Long settled) {
        LocalDate at = asOf != null ? asOf : LocalDate.now(IST);
        // A closed loan's balance is frozen at the day it closed — otherwise a loan closed months ago
        // keeps accruing late penalty against "today" and reports a phantom balance.
        if (loan.getClosedOn() != null && at.isAfter(loan.getClosedOn())) {
            at = loan.getClosedOn();
        }
        LoanMath math = pricing.mathFor(loan);
        int tenureDays = (int) ChronoUnit.DAYS.between(loan.getDisbursedOn(), loan.getDueDate());
        int daysToAsOf = (int) Math.max(0L, ChronoUnit.DAYS.between(loan.getDisbursedOn(), at));
        int interestDays = math.pricing().upfront() ? 0 : Math.min(daysToAsOf, tenureDays + loan.getGraceDays());
        // The current due cycle's late days, within what is left of the lifetime cap (EXT-26). Late charge
        // from earlier cycles that repayments paid is carried, so principal stays whole after an extension.
        // A restructured loan's late charge was frozen into its EMI instalments (emi.penal off, Phase 6c).
        int penaltyDays = loan.getRestructuredAt() != null ? 0
                : math.chargeablePenalDays(loan.getDueDate(), at, loan.getGraceDays(), loan.getPenalDaysUsed());
        long carried = loan.getPenalCarriedPaise();
        long interest = math.accruedInterestPaise(loan.getPrincipal(), interestDays);
        long penalty = math.latePenaltyPaise(loan.getPrincipal(), penaltyDays) + carried;
        long formulaOwed = Math.max(0L, math.outstandingPaise(loan.getPrincipal(), interestDays, penaltyDays, 0L)
                + carried - verified);
        long owed = settled != null ? Math.min(formulaOwed, Math.max(0L, settled - verified)) : formulaOwed;
        // Report the days actually CHARGED (penalty is capped inside latePenaltyPaise), so the
        // displayed "2%/day × N days" reconciles with penaltyPaise instead of overstating it.
        int chargedPenaltyDays = penaltyDays;
        // Interest deducted at disbursal is shown, never owed (PLAN Phase 10b R4).
        long prepaid = loan.getUpfrontInterestPaise() != null ? loan.getUpfrontInterestPaise() : 0L;
        return new OutstandingBreakdown(owed, interest, penalty, verified, settled,
                Math.max(0, interestDays), chargedPenaltyDays, prepaid);
    }

    /**
     * The batched twin of {@link #outstandingBreakdownAsOf}: the full itemised breakdown per loan in a
     * fixed number of queries (one verified-payment sum, one settlement lookup) regardless of how many
     * loans are passed — where the per-loan call costs 3 queries each (the loan reload inside
     * {@code requireLoan}, the verified-payment sum, and the settlement lookup), so a 300-row register
     * would otherwise be ~900. Callers must already hold the {@link Loan} rows (no reload here).
     *
     * <p>Runs <b>the same formula</b> {@link #outstandingBreakdownAsOf} does — literally the same
     * private helper, not a copy: the loan register, the repay page, and collections must all agree on
     * "amount owed", so the math is not allowed to fork into a second implementation.
     *
     * <p>The verified-payment sums come from one grouped query
     * ({@link PaymentRepository#sumAmountByLoanIdInAndStatus}) and the approved settlements from one
     * batched port read ({@link SettlementDirectory#approvedSettlementAmounts}) — a loan with no
     * approved settlement is simply absent from that map, which is the same thing the single-loan
     * {@code Optional.empty()} means.
     *
     * @param loans the loans to price (any subset; duplicates/foreign loans are harmless)
     * @param asOf  the as-of date (defaults to today when null)
     * @return loan id → itemised breakdown, for every loan passed in
     */
    @Transactional(readOnly = true)
    public Map<Long, OutstandingBreakdown> outstandingBreakdownsForAll(List<Loan> loans, LocalDate asOf) {
        if (loans.isEmpty()) {
            return Map.of();
        }
        // Resolve "today" ONCE for the whole batch: pricing a long page across midnight IST must not
        // put the first and last rows on different dates.
        LocalDate at = asOf != null ? asOf : LocalDate.now(IST);
        List<Long> loanIds = loans.stream().map(Loan::getId).toList();
        Map<Long, Long> verifiedByLoanId = paymentRepository
                .sumAmountByLoanIdInAndStatus(loanIds, PaymentStatus.VERIFIED).stream()
                .collect(Collectors.toMap(PaymentRepository.LoanAmount::getLoanId,
                        PaymentRepository.LoanAmount::getTotal));
        Map<Long, Long> settledByLoanId = settlementDirectory.approvedSettlementAmounts(loanIds);

        Map<Long, OutstandingBreakdown> result = new HashMap<>();
        for (Loan loan : loans) {
            long verified = verifiedByLoanId.getOrDefault(loan.getId(), 0L);
            result.put(loan.getId(), breakdown(loan, at, verified, settledByLoanId.get(loan.getId())));
        }
        return result;
    }

    /**
     * The batched twin of {@link #outstandingAsOf}: the same penalty/prepayment-aware balance for a
     * whole set of loans in a small, fixed number of queries instead of one round trip per loan.
     * A thin projection of {@link #outstandingBreakdownsForAll} — same queries, same formula, just the
     * net figure.
     *
     * @param loans the loans to price (any subset; duplicates/foreign loans are harmless)
     * @param asOf  the as-of date (defaults to today when null)
     * @return loan id → outstanding paise, for every loan passed in
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> outstandingForAll(List<Loan> loans, LocalDate asOf) {
        return outstandingBreakdownsForAll(loans, asOf).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().outstandingPaise()));
    }

    /**
     * The approved full-and-final settlement amount (paise) for a loan, if collections has one — for
     * surfacing on the borrower's repay page (so the capped "pay today" figure is shown as a
     * settlement, not the normal balance). Empty when no approved settlement applies.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> approvedSettlementAmount(Long loanId) {
        return settlementDirectory.approvedSettlementAmount(loanId);
    }

    /**
     * Recompute the cached balance and close the loan at zero.
     *
     * <p><b>Settlement is evaluated as of the payment date, never the verification date.</b> The
     * accountant's confirmation is bookkeeping, not an economic event: a borrower who paid in full on
     * the 5th owes nothing further because it was verified on the 7th. Evaluating at "now" charged
     * two extra days of interest (and late penalty), which could leave a fully-paid loan open and
     * accruing forever — every subsequent recompute moved the goalposts by another day. So the
     * closure question is asked as of the latest VERIFIED payment's {@code paidOn}.
     *
     * <p>A REJECTED payment is never summed, so rejecting one leaves accrual running normally — which
     * is the intended asymmetry: a failed payment costs the borrower the days, a slow verifier does not.
     *
     * <p>When the loan does <em>not</em> clear (a partial payment, or nothing verified yet) the cached
     * balance keeps tracking today, because that is still what the borrower owes if they pay now.
     */
    @Transactional
    public void recomputeOutstanding(Long loanId) {
        Loan loan = requireLoan(loanId);
        LocalDate settledOn = latestVerifiedPaidOn(loanId).orElse(LocalDate.now(IST));
        // Use the authoritative penalty-aware balance: an overdue loan must not close just because
        // the borrower paid the no-penalty stored total — the accrued late penalty is still owed.
        long owedAtPayment = outstandingAsOf(loanId, settledOn);

        if (owedAtPayment > 0L) {
            loan.setOutstanding(outstandingAsOf(loanId, LocalDate.now(IST)));
            refreshRepaymentStatus(loan);
            loanRepository.save(loan);
            return;
        }
        boolean newlyClosed = loan.getStatus() != LoanStatus.CLOSED;
        LoanLifecycle stateBefore = loan.getLifecycleStatus();
        Long cachedBefore = loan.getOutstanding();
        loan.setOutstanding(0L);
        // Any open state → CLOSED once a verified reconciliation leaves nothing owed (PLAN Phase 3).
        loan.moveTo(LoanLifecycle.CLOSED);
        refreshRepaymentStatus(loan);
        // Freeze the balance as of the day it actually closed — the same date the closure was judged
        // on — so outstandingBreakdownAsOf never keeps accruing penalty past it for a closed loan.
        loan.setClosedOn(settledOn);
        loanRepository.save(loan);
        // Mirror full repayment onto the application aggregate (ACTIVE/OVERDUE → CLOSED).
        // A settlement closure says so in the audit trail: the borrower did not pay the full formula balance.
        var settlement = settlementDirectory.approvedSettlementAmount(loanId);
        if (newlyClosed) {
            // CC-18: how it closed; a settlement never moves the ladder.
            loan.setClosureType(settlement.isPresent() ? "SETTLED" : "REPAID");
            recordLateReversed(loan, stateBefore, cachedBefore, settledOn);
            // An open extension offer ends with the loan; any charge paid for it is refunded (EXT-06, CC-69).
            extensionLedger.onLoanClosed(loanId);
            // Credit ladder (PLAN Phase 2). On-time is judged by the bank date of the closing payment
            // (paid_on, a date: the start of that IST day), never the verification time (CC-05).
            creditLevels.onLoanClosed(loan, settledOn.atStartOfDay(IST).toInstant(), settlement.isPresent());
        }
        if (settlement.isPresent()) {
            applicationFlowService.closeForLoan(loanId, "Closed by approved settlement of "
                    + java.text.NumberFormat.getCurrencyInstance(java.util.Locale.of("en", "IN"))
                            .format(settlement.get() / 100.0)
                    + " (full & final)");
        } else {
            applicationFlowService.closeForLoan(loanId);
        }
        // DSA commission maturity: flip ACCRUED -> PAYABLE (or VOID on an approved settlement /
        // default / write-off). A no-op when this loan has no DSA commission.
        dsaCommissionService.onLoanClosed(loanId);
    }

    /**
     * CC-05: paid by the due date (bank {@code paid_on}) but verified after the loan went overdue. The waterfall
     * already charged no late fee for that payment; the overdue state and the late charge the cached balance had
     * accrued meanwhile are reversed, and a {@code balance_adjustment} row says so.
     */
    private void recordLateReversed(Loan loan, LoanLifecycle stateBefore, Long cachedBefore, LocalDate paidOn) {
        if ((stateBefore != LoanLifecycle.OVERDUE_D1 && stateBefore != LoanLifecycle.OVERDUE_REVIEW)
                || paidOn.isAfter(loan.getDueDate())) {
            return;
        }
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        before.put("lifecycleStatus", stateBefore.name());
        before.put("outstandingPaise", cachedBefore);
        allocator.adjustment(loan.getId(), loan.getCustomerId(), "LATE_REVERSED", before,
                Map.of("lifecycleStatus", LoanLifecycle.CLOSED.name(), "outstandingPaise", 0L,
                        "paidOn", paidOn.toString()), null, "PAID_ON_TIME", Instant.now());
    }

    /**
     * EXT-14 "Treat as extension payment": the borrower sent the extension charge as a plain repayment. While it is
     * still waiting for verification and equals the quote of the loan's ACCEPTED extension for its bank date, the
     * Accountant re-files it as that extension's payment; verifying it then moves the due date as usual.
     */
    @Transactional
    public Payment treatAsExtension(Long loanId, Long paymentId, java.util.UUID extensionId) {
        var actor = ActorContext.get();
        if (!java.util.Set.of("ACCOUNTANT", "FINANCE", "ADMIN").contains(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ACCOUNTANT, FINANCE or ADMIN");
        }
        Payment payment = paymentRepository.findById(paymentId)
                .filter(p -> p.getLoanId().equals(loanId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment", String.valueOf(paymentId)));
        if (payment.getStatus() != PaymentStatus.PENDING_VERIFICATION || payment.isExtension()) {
            throw new BusinessException("STALE_STATE", "Only a repayment waiting for verification can be re-filed");
        }
        extensionLedger.lock(loanId);
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
        extensionLedger.requirePayable(loan, extensionId, payment.getAmount(), payment.getPaidOn());
        payment.setPurpose(PaymentPurpose.EXTENSION);
        payment.setExtensionId(extensionId);
        payment.setPartial(false);
        Payment saved = paymentRepository.saveAndFlush(payment);
        extensionLedger.markPaymentPending(extensionId);
        // The loan no longer has a repayment waiting on this money.
        refreshRepaymentStatus(loan);
        return saved;
    }

    /**
     * CC-10: a verified repayment found bounced or reversed by the bank. ACCOUNTANT or ADMIN, never the person
     * who recorded or verified it (maker-checker). The payment becomes REVERSED and stops counting; its split is
     * undone (an open refund case for it is cancelled); a closed loan reopens to the state its dates imply
     * (RESTRUCTURED if it was) and any level its closure gave is taken back with a history row. Audited as a
     * {@code balance_adjustment}. An extension payment moved a due date and is not reversed here.
     */
    @Transactional
    public Payment reversePayment(Long paymentId, String reason) {
        var actor = ActorContext.get();
        if (!java.util.Set.of("ACCOUNTANT", "FINANCE", "ADMIN").contains(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ACCOUNTANT, FINANCE or ADMIN");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "Say why the payment is reversed.");
        }
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", String.valueOf(paymentId)));
        if (payment.isExtension()) {
            throw new BusinessException("EXTENSION_PAYMENT",
                    "An extension payment moved the due date; correct it with a manual adjustment.");
        }
        extensionLedger.lock(payment.getLoanId());
        if (payment.getStatus() != PaymentStatus.VERIFIED) {
            throw new BusinessException("PAYMENT_NOT_VERIFIED", "Only a verified payment can be reversed.");
        }
        Long by = staffIdOrNull();
        SeparationOfDutiesGuard.enforce(by, payment.getRecordedBy(), payment.getDecidedBy());
        Loan loan = requireLoan(payment.getLoanId());
        boolean wasClosed = loan.getLifecycleStatus() == LoanLifecycle.CLOSED;
        if (wasClosed && loanRepository.findByCustomerId(loan.getCustomerId()).stream()
                .anyMatch(l -> !l.getId().equals(loan.getId()) && l.getLifecycleStatus() != LoanLifecycle.CLOSED)) {
            throw new BusinessException("ANOTHER_LOAN_OPEN",
                    "The customer has another loan open; reverse this one with a manual adjustment.");
        }
        Instant now = Instant.now();
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        before.put("lifecycleStatus", loan.getLifecycleStatus().name());
        before.put("outstandingPaise", loan.getOutstanding());
        before.put("closedOn", String.valueOf(loan.getClosedOn()));
        before.put("allocation", allocator.unallocate(payment, now));

        payment.setStatus(PaymentStatus.REVERSED);
        payment.setReversedBy(by);
        payment.setReversedAt(now);
        payment.setReversalReason(reason.trim());
        paymentRepository.saveAndFlush(payment);

        if (wasClosed) {
            loan.setClosedOn(null);
            loan.setClosureType(null);
            if (loan.getRestructuredAt() != null) {
                loan.moveTo(LoanLifecycle.RESTRUCTURED);
            } else {
                var product = pricing.product(loan.getProductVersionId());
                Instant dueAt = loan.getDueAt() != null ? loan.getDueAt() : LoanMath.dueAt(loan.getDueDate(), product);
                loan.moveTo(LoanLifecycle.ACTIVE);
                loan.advanceTo(LoanLifecycle.forTime(now, dueAt, product.zone(), product.overdueReviewDpd()));
            }
            loanRepository.save(loan);
            applicationFlowService.reopenForLoan(loan.getId(), "Payment " + paymentId + " reversed: " + reason.trim());
            creditLevels.onClosureReversed(loan);
        }
        recomputeOutstanding(loan.getId());
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("lifecycleStatus", loan.getLifecycleStatus().name());
        after.put("outstandingPaise", loan.getOutstanding());
        after.put("paymentId", paymentId);
        allocator.adjustment(loan.getId(), loan.getCustomerId(), "PAYMENT_REVERSAL", before, after, by,
                "PAYMENT_REVERSED", reason.trim(), now);
        return payment;
    }

    /**
     * The figure the closure decision is made on: the balance as of the day the borrower last
     * actually paid. Zero means the loan is settled, however late the verification was. Exposed
     * read-only so the ADMIN maintenance sweep can report what a recompute would do before doing it.
     */
    @Transactional(readOnly = true)
    public long settlementBalance(Long loanId) {
        return outstandingAsOf(loanId, latestVerifiedPaidOn(loanId).orElse(LocalDate.now(IST)));
    }

    /**
     * {@code repayment_status} from the payments on file: PAID once closed, else a payment awaiting
     * the accountant, else part-paid (a verified payment), else unpaid (CC-09, CC-11).
     */
    private void refreshRepaymentStatus(Loan loan) {
        if (loan.getLifecycleStatus() == LoanLifecycle.CLOSED) {
            loan.setRepaymentStatus(RepaymentStatus.PAID);
            return;
        }
        List<Payment> payments = paymentRepository.findByLoanId(loan.getId()).stream()
                .filter(p -> !p.isExtension()).toList();
        loan.setRepaymentStatus(
                payments.stream().anyMatch(p -> p.getStatus() == PaymentStatus.PENDING_VERIFICATION)
                        ? RepaymentStatus.PAID_PENDING_VERIFICATION
                        : payments.stream().anyMatch(p -> p.getStatus() == PaymentStatus.VERIFIED)
                                ? RepaymentStatus.PARTIAL
                                : RepaymentStatus.UNPAID);
    }

    /** The date the borrower last actually paid, over VERIFIED repayments only (not extension charges). */
    private java.util.Optional<LocalDate> latestVerifiedPaidOn(Long loanId) {
        return paymentRepository.findByLoanId(loanId).stream()
                .filter(p -> !p.isExtension() && p.getStatus() == PaymentStatus.VERIFIED && p.getPaidOn() != null)
                .map(Payment::getPaidOn)
                .max(LocalDate::compareTo);
    }

    /**
     * A borrower may only record payments against their <em>own</em> loan.
     *
     * <p>Without this any borrower could post a payment onto a stranger's loan: the row lands
     * PENDING_VERIFICATION on that loan, and the only thing standing between it and a real balance
     * reduction is an accountant noticing. Staff roles are unrestricted — recording on a borrower's
     * behalf (a branch walk-in, a phoned-in reference) is legitimate.
     */
    /**
     * Ownership guard for the by-id loan reads: a BORROWER may only read their OWN loan (its
     * {@code customer_id} must equal the JWT subject); any staff role but DSA may read any loan.
     *
     * <p>Closes an IDOR on the three reads that had no guard at all — without this a borrower could
     * fetch another customer's loan, balance or repayment history by guessing the id, all of which
     * carry that customer's money. DSA is excluded for the reason spelled out in
     * {@code CustomerService.rejectDsa}: an external commission agent must never reach customer data.
     * (Anonymous callers never get here — {@code SecurityConfig} requires auth on {@code /api/**}.)
     */
    @Transactional(readOnly = true)
    public Loan requireReadableLoan(Long loanId) {
        var actor = com.navix.common.security.ActorContext.get();
        if (actor != null && "DSA".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "DSAs cannot view loans");
        }
        Loan loan = requireLoan(loanId);
        requireOwnLoanIfBorrower(loan, "Not your loan");
        return loan;
    }

    private void requireOwnLoanIfBorrower(Loan loan) {
        requireOwnLoanIfBorrower(loan, "You can only record payments on your own loan");
    }

    private void requireOwnLoanIfBorrower(Loan loan, String message) {
        var actor = com.navix.common.security.ActorContext.get();
        if (actor == null || !"BORROWER".equals(actor.role())) {
            return;
        }
        if (!String.valueOf(loan.getCustomerId()).equals(actor.id())) {
            throw new BusinessException("FORBIDDEN", message);
        }
    }

    private Loan requireLoan(Long loanId) {
        return loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
    }
}
