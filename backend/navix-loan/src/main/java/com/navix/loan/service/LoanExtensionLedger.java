package com.navix.loan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.collections.ExtensionDirectory;
import com.navix.common.collections.ExtensionDirectory.ExtensionRef;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ConflictException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.extension.ExtensionFacts;
import com.navix.common.extension.ExtensionQuote;
import com.navix.common.notification.event.ExtensionActivatedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.SeparationOfDutiesGuard;
import com.navix.loan.domain.EmiStatus;
import com.navix.loan.domain.LoanHold;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.PaymentMethod;
import com.navix.loan.domain.PaymentPurpose;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.domain.ProductSnapshot;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.MandateDebit;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.MandateDebitRepository;
import com.navix.loan.repository.PaymentRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The loan side of a collections-led extension (PLAN Phase 6b): the quote, the eligibility facts, the
 * extension payment, and {@link #activate} — the only code that moves a due date.
 *
 * <p>Money model: an extension payment never reduces principal and is never in the "verified" sum. It pays
 * the fee + GST + one more tenure of interest, and the late charge to its bank date. After activation the
 * late-charge window restarts from the new due date; the days already charged count toward the lifetime cap.
 *
 * <p>Every write takes the loan advisory lock NACH presentment uses, so an offer, a payment verification and
 * a debit never interleave on one loan (EXT-20, EXT-21).
 */
@Service
@RequiredArgsConstructor
public class LoanExtensionLedger {

    /** The loan states an extension may be offered in. */
    static final Set<LoanLifecycle> ELIGIBLE = EnumSet.of(LoanLifecycle.DUE_TODAY, LoanLifecycle.OVERDUE_D1,
            LoanLifecycle.OVERDUE_REVIEW);
    private static final Set<MandateDebit.Status> IN_FLIGHT =
            EnumSet.of(MandateDebit.Status.SCHEDULED, MandateDebit.Status.IN_PROGRESS);

    private final LoanRepository loans;
    private final PaymentRepository payments;
    private final com.navix.loan.repository.EmiConversionRepository emis;
    private final MandateDebitRepository debits;
    private final PricingService pricing;
    private final RepaymentScheduleService schedules;
    private final ExtensionDirectory extensions;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * The extension charge for a payment on {@code paidOn}: fee + GST + interest for one more tenure, plus the
     * late charge of the current cycle to {@code paidOn} (within the lifetime cap) and any late charge from
     * earlier cycles not yet paid, less what repayments already paid of it. Pure.
     *
     * @param repaymentPenalPaid late charge paid by repayments so far (PENAL allocations of REPAYMENT payments)
     */
    static ExtensionQuote quote(Loan loan, LoanMath math, LocalDate paidOn, long repaymentPenalPaid) {
        long principal = loan.getPrincipal();
        int days = math.chargeablePenalDays(loan.getDueDate(), paidOn, loan.getGraceDays(), loan.getPenalDaysUsed());
        long late = Math.max(0L, math.latePenaltyPaise(principal, days) + loan.getPenalCarriedPaise() - repaymentPenalPaid);
        return new ExtensionQuote(math.processingFeePaise(principal), math.gstPaise(principal),
                math.interestPaise(principal, math.pricing().tenureDays()), late, days, paidOn);
    }

    @Transactional(readOnly = true)
    public ExtensionQuote quote(Loan loan, LocalDate paidOn) {
        return quote(loan, pricing.mathFor(loan), paidOn != null ? paidOn : today(), repaymentPenalPaid(loan.getId()));
    }

    /** The new due date an extension gives: the current due date + one tenure (no holiday shift). */
    public LocalDate newDueDate(Loan loan) {
        return loan.getDueDate().plusDays(pricing.mathFor(loan).pricing().tenureDays());
    }

    /** Eligibility (a reason code, or null) and the quote for {@code paidOn}. */
    @Transactional(readOnly = true)
    public ExtensionFacts facts(Long loanId, LocalDate paidOn) {
        Loan loan = requireLoan(loanId);
        ProductSnapshot product = pricing.product(loan.getProductVersionId());
        LoanLifecycle state = effectiveState(loan, product);
        String reason = null;
        if (state == LoanLifecycle.CLOSED) {
            reason = "LOAN_CLOSED";
        } else if (state == LoanLifecycle.RESTRUCTURE_PENDING || state == LoanLifecycle.RESTRUCTURED) {
            reason = "RESTRUCTURED";
        } else if (!ELIGIBLE.contains(state)) {
            reason = "NOT_DUE_YET";
        } else if (loan.getHoldStatus() != LoanHold.NONE) {
            reason = "ON_HOLD";
        } else if (payments.findByLoanId(loanId).stream()
                .anyMatch(p -> !p.isExtension() && p.getStatus() == PaymentStatus.PENDING_VERIFICATION)) {
            reason = "REPAYMENT_PENDING";
        } else if (debits.existsByLoanIdAndStatusIn(loanId, IN_FLIGHT)) {
            reason = "DEBIT_IN_FLIGHT";
        } else if (emis.existsByLoanIdAndStatusIn(loanId, EmiStatus.OPEN)) {
            reason = "EMI_OPEN";
        } else if (loan.getExtensionCount() >= product.extensionMax()) {
            reason = "EXTENSION_CAP_REACHED";
        }
        return new ExtensionFacts(loanId, loan.getCustomerId(), state.name(), loan.getHoldStatus().name(),
                loan.getDueDate(), loan.getOriginalDueDate(), newDueDate(loan), loan.getExtensionCount(),
                product.extensionMax(), reason, quote(loan, paidOn));
    }

    /** The persisted state, moved on by the clock when the lifecycle job has not run yet. */
    LoanLifecycle effectiveState(Loan loan, ProductSnapshot product) {
        LoanLifecycle state = loan.getLifecycleStatus();
        if (loan.getDueDate() == null || !LoanLifecycleService.TIMED.contains(state)) {
            return state;
        }
        Instant dueAt = loan.getDueAt() != null ? loan.getDueAt() : LoanMath.dueAt(loan.getDueDate(), product);
        LoanLifecycle byClock = LoanLifecycle.forTime(clock.instant(), dueAt, product.zone(), product.overdueReviewDpd());
        return state.isBefore(byClock) ? byClock : state;
    }

    /** The loan advisory lock (NACH's), held to the end of the caller's transaction. */
    public void lock(Long loanId) {
        debits.lockLoan(DebitService.LOCK_NAMESPACE | loanId);
    }

    /**
     * The borrower (or ADMIN) is about to record a payment for {@code extensionId}: it must be the loan's
     * ACCEPTED extension, still open, for exactly the quote on {@code paidOn} (never auto-extend on a short
     * payment, EXT-16). Moves it to PAYMENT_PENDING once the caller has saved the payment.
     */
    public ExtensionRef requirePayable(Loan loan, UUID extensionId, long amountPaise, LocalDate paidOn) {
        if (extensionId == null) {
            throw new BusinessException("EXTENSION_REQUIRED", "Say which extension this payment is for.");
        }
        lock(loan.getId());
        ExtensionRef ext = extensions.find(extensionId)
                .filter(e -> e.loanId().equals(loan.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("LoanExtension", String.valueOf(extensionId)));
        if (!"ACCEPTED".equals(ext.status())) {
            throw new ConflictException("STALE_STATE", "This extension is not waiting for a payment.", ext.status());
        }
        if (clock.instant().isAfter(ext.expiresAt())) {
            throw new ConflictException("EXTENSION_EXPIRED", "This extension offer has expired.", "EXPIRED");
        }
        if (debits.existsByLoanIdAndStatusIn(loan.getId(), IN_FLIGHT)) {
            throw new BusinessException("DEBIT_IN_FLIGHT", "An auto-debit is in progress on this loan.");
        }
        requireQuoted(loan, amountPaise, paidOn);
        return ext;
    }

    public void markPaymentPending(UUID extensionId) {
        extensions.paymentPending(extensionId);
    }

    public void markPaymentRejected(UUID extensionId) {
        extensions.paymentRejected(extensionId);
    }

    /** Cancel any open extension (EXT-06, CC-69) or EMI offer on a loan that just closed. */
    public void onLoanClosed(Long loanId) {
        extensions.cancelOpen(loanId, "LOAN_CLOSED", clock.instant());
        emis.cancelOpen(loanId, clock.instant());
    }

    /**
     * Collections logged the payment and the Accountant validated it: record it (VERIFIED, EXTENSION) and
     * activate. A UTR already on file for this extension returns that payment; used for anything else it is
     * refused.
     */
    @Transactional
    public Long applyCollected(Long loanId, UUID extensionId, long amountPaise, LocalDate paidOn, String txnRef,
                               String proofRef) {
        lock(loanId);
        Loan loan = requireLoan(loanId);
        String utr = RepaymentService.normaliseUtr(txnRef);
        if (utr != null) {
            var existing = payments.findFirstByTxnRefAndStatusNot(utr, PaymentStatus.REJECTED);
            if (existing.isPresent()) {
                Payment p = existing.get();
                if (p.isExtension() && extensionId.equals(p.getExtensionId()) && p.getStatus() == PaymentStatus.VERIFIED) {
                    return p.getId();
                }
                throw p.isExtension() ? new BusinessException("DUPLICATE_UTR", "This payment reference is already on file.")
                        : new BusinessException("UTR_USED_FOR_OTHER_PURPOSE",
                                "This payment reference is already on file for a repayment.");
            }
        }
        Payment payment = new Payment();
        payment.setLoanId(loanId);
        payment.setAmount(amountPaise);
        payment.setMethod(PaymentMethod.BANK_TRANSFER);
        payment.setStatus(PaymentStatus.VERIFIED);
        payment.setPurpose(PaymentPurpose.EXTENSION);
        payment.setExtensionId(extensionId);
        payment.setTxnRef(utr);
        payment.setProofUrl(proofRef);
        payment.setPaidOn(paidOn != null ? paidOn : today());
        payment.setDecidedBy(staffIdOrNull());
        payment.setDecidedAt(clock.instant());
        try {
            payment = payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("STALE_STATE", "This extension already has a payment on file.", null);
        }
        activate(loan, payment, true);
        return payment.getId();
    }

    /**
     * The Accountant verified the borrower's extension payment. Activates the extension when it is still
     * waiting for this payment; returns false when the extension was cancelled meanwhile (the loan closed),
     * so the caller refunds the money (CC-69).
     */
    public boolean onVerified(Loan loan, Payment payment) {
        lock(loan.getId());
        ExtensionRef ext = requireExtension(payment.getExtensionId());
        if ("ACTIVE".equals(ext.status()) && payment.getId().equals(ext.paymentId())) {
            throw new ConflictException("ALREADY_VERIFIED",
                    "Already verified at " + ext.activatedAt() + ".", ext.status());
        }
        if (!"PAYMENT_PENDING".equals(ext.status())) {
            return false;
        }
        activate(loan, payment, false);
        return true;
    }

    /**
     * Move the due date. Under the loan lock, with the extension PAYMENT_PENDING and the payment equal to the
     * quote for its bank date: allocation rows, due history, due + one tenure, the count, lifecycle back to
     * ACTIVE (which clears IN_COLLECTIONS), a new schedule version, a balance_adjustment row, the extension
     * ACTIVE, and the event for the addendum PDF and the borrower.
     */
    private void activate(Loan loan, Payment payment, boolean collected) {
        ExtensionRef ext = requireExtension(payment.getExtensionId());
        if (!"PAYMENT_PENDING".equals(ext.status())) {
            throw new ConflictException("STALE_STATE", "This extension is not waiting for a payment.", ext.status());
        }
        SeparationOfDutiesGuard.extensionPaymentVerifier(staffIdOrNull(), ext.proposedBy(), ext.approvedBy());
        if (debits.existsByLoanIdAndStatusIn(loan.getId(), IN_FLIGHT)) {
            throw new BusinessException("DEBIT_IN_FLIGHT", "An auto-debit is in progress on this loan; verify after it ends.");
        }
        LocalDate paidOn = payment.getPaidOn() != null ? payment.getPaidOn() : today();
        long repaymentPenal = repaymentPenalPaid(loan.getId());
        ExtensionQuote q = quote(loan, pricing.mathFor(loan), paidOn, repaymentPenal);
        if (q.totalPaise() != payment.getAmount()) {
            throw new BusinessException("EXTENSION_AMOUNT_MISMATCH",
                    "The extension costs " + q.totalPaise() + " paise for a payment on " + paidOn
                            + "; this payment is " + payment.getAmount() + ". Reject it or record it as a part payment.");
        }
        Instant now = clock.instant();
        Map<String, Object> before = snapshot(loan);

        allocate(payment, "EXT_FEE", q.feePaise());
        allocate(payment, "EXT_GST", q.gstPaise());
        allocate(payment, "EXT_INTEREST", q.interestPaise());
        allocate(payment, "PENAL", q.lateChargePaise());

        LocalDate oldDue = loan.getDueDate();
        Instant oldDueAt = loan.getDueAt() != null ? loan.getDueAt()
                : LoanMath.dueAt(oldDue, pricing.product(loan.getProductVersionId()));
        closeDueCycle(loan, oldDue, oldDueAt, q.lateDays(), ext.id(), now);

        LocalDate newDue = newDueDate(loan);
        loan.setDueDate(newDue);
        loan.setDueAt(LoanMath.dueAt(newDue, pricing.product(loan.getProductVersionId())));
        loan.setExtensionCount(loan.getExtensionCount() + 1);
        loan.setPenalDaysUsed(loan.getPenalDaysUsed() + q.lateDays());
        // Every late charge to paid_on is now settled: what repayments paid of it stays counted against them.
        loan.setPenalCarriedPaise(repaymentPenal);
        loan.moveTo(LoanLifecycle.ACTIVE);
        loans.save(loan);
        jdbc.update("insert into loan_due_history (loan_id, seq, due_date, due_at, from_at) values (?, "
                        + "(select coalesce(max(seq), 0) + 1 from loan_due_history where loan_id = ?), ?, ?, ?)",
                loan.getId(), loan.getId(), newDue, Timestamp.from(loan.getDueAt()), Timestamp.from(now));

        long principalPaid = jdbc.queryForObject("select coalesce(sum(a.amount_paise), 0) from payment_allocation a"
                + " where a.loan_id = ? and a.component = 'PRINCIPAL'", Long.class, loan.getId());
        int[] versions = schedules.writeExtension(loan, Math.max(0L, loan.getPrincipal() - principalPaid));
        Map<String, Object> after = snapshot(loan);
        after.put("extensionId", ext.id().toString());
        after.put("chargePaise", q.totalPaise());
        after.put("lateChargePaise", q.lateChargePaise());
        after.put("source", collected ? "COLLECTIONS" : "BORROWER");
        jdbc.update("insert into balance_adjustment (loan_id, customer_id, kind, before, after, schedule_version_from,"
                        + " schedule_version_to, calc_version, approver_id, reason_code, acceptance_evidence, at)"
                        + " values (?, ?, 'EXTENSION', cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?, 'EXTENSION',"
                        + " cast(? as jsonb), ?)",
                loan.getId(), loan.getCustomerId(), toJson(before), toJson(after), versions[0] == 0 ? null : versions[0],
                versions[1], loan.getPricingVersionId(), ext.approvedBy(),
                toJson(Map.of("acceptedAt", String.valueOf(ext.acceptedAt()), "paymentId", payment.getId())),
                Timestamp.from(now));
        extensions.activated(ext.id(), payment.getId(), now);
        events.publishEvent(new ExtensionActivatedEvent(ext.id(), loan.getId(), loan.getCustomerId(),
                loan.getExtensionCount(), pricing.product(loan.getProductVersionId()).extensionMax(),
                payment.getAmount(), oldDue, newDue, now));
    }

    /** Close the cycle that is ending; the first extension also writes the original cycle (seq 1). */
    private void closeDueCycle(Loan loan, LocalDate due, Instant dueAt, int penalDays, UUID extensionId, Instant now) {
        int closed = jdbc.update("update loan_due_history set to_at = ?, penal_days = ?, extension_id = ?"
                + " where loan_id = ? and to_at is null", Timestamp.from(now), penalDays, extensionId, loan.getId());
        if (closed == 0) {
            Instant from = loan.getDisbursedOn() != null
                    ? loan.getDisbursedOn().atStartOfDay(clock.getZone()).toInstant() : now;
            jdbc.update("insert into loan_due_history (loan_id, seq, due_date, due_at, from_at, to_at, penal_days,"
                            + " extension_id) values (?, 1, ?, ?, ?, ?, ?, ?)",
                    loan.getId(), due, Timestamp.from(dueAt), Timestamp.from(from), Timestamp.from(now), penalDays,
                    extensionId);
        }
    }

    private void allocate(Payment payment, String component, long amount) {
        if (amount > 0) {
            jdbc.update("insert into payment_allocation (payment_id, loan_id, component, amount_paise) values (?, ?, ?, ?)",
                    payment.getId(), payment.getLoanId(), component, amount);
        }
    }

    private void requireQuoted(Loan loan, long amountPaise, LocalDate paidOn) {
        ExtensionQuote q = quote(loan, paidOn);
        if (q.totalPaise() != amountPaise) {
            throw new BusinessException("EXTENSION_AMOUNT_MISMATCH",
                    "The extension costs " + q.totalPaise() + " paise for a payment on " + q.paidOn() + ".");
        }
    }

    /** Late charge repayments have paid so far (an extension's own PENAL share is not counted). */
    private long repaymentPenalPaid(Long loanId) {
        Long paid = jdbc.queryForObject("select coalesce(sum(a.amount_paise), 0) from payment_allocation a"
                + " join payment p on p.id = a.payment_id where a.loan_id = ? and a.component = 'PENAL'"
                + " and p.purpose = 'REPAYMENT'", Long.class, loanId);
        return paid == null ? 0L : paid;
    }

    private ExtensionRef requireExtension(UUID id) {
        return extensions.find(id).orElseThrow(() -> new ResourceNotFoundException("LoanExtension", String.valueOf(id)));
    }

    private Loan requireLoan(Long loanId) {
        return loans.findById(loanId).orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
    }

    private static Map<String, Object> snapshot(Loan loan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dueDate", String.valueOf(loan.getDueDate()));
        m.put("dueAt", String.valueOf(loan.getDueAt()));
        m.put("extensionCount", loan.getExtensionCount());
        m.put("lifecycleStatus", loan.getLifecycleStatus().name());
        m.put("principalPaise", loan.getPrincipal());
        m.put("penalDaysUsed", loan.getPenalDaysUsed());
        return m;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return json.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static Long staffIdOrNull() {
        try {
            return Long.valueOf(ActorContext.get().id());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
