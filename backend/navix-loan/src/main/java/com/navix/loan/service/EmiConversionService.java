package com.navix.loan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.collections.ExtensionDirectory;
import com.navix.common.collections.SettlementDirectory;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ConflictException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.idempotency.IdempotencyService;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.security.ActorContext;
import com.navix.common.security.SeparationOfDutiesGuard;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.loan.domain.EmiStatus;
import com.navix.loan.domain.LoanHold;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.domain.ProductSnapshot;
import com.navix.loan.dto.EmiDtos.AcceptRequest;
import com.navix.loan.dto.EmiDtos.BorrowerEmiView;
import com.navix.loan.dto.EmiDtos.EmiPanel;
import com.navix.loan.dto.EmiDtos.EmiRequest;
import com.navix.loan.dto.EmiDtos.EmiView;
import com.navix.loan.dto.EmiDtos.InstalmentView;
import com.navix.loan.dto.EmiDtos.OtpSentView;
import com.navix.loan.entity.EmiConversion;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.MandateDebit;
import com.navix.loan.repository.EmiConversionRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.MandateDebitRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.service.RepaymentScheduleService.Instalment;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recovery EMI conversion (PLAN Phase 6c; flag {@code emi-conversion}, OFF at launch). A collections officer or
 * the Collection Head requests it on an overdue loan, an ADMIN (only) approves it (requester ≠ approver), and the
 * borrower accepts with an OTP in the app. Acceptance activates it: what is owed that day (principal still open +
 * late charge to date) is split into two instalments (three from the configured amount, ₹7,000 and ₹10,000), every
 * {@code emi_gap_days} days from the conversion date, the remainder paise on the last; the loan goes
 * RESTRUCTURED, a new EMI schedule version supersedes the active one, and a {@code balance_adjustment} row
 * records it. No late charge accrues after that (config {@code emi.penal} default off); EMI interest follows
 * {@code navix.emi.interest-mode} (NONE).
 *
 * <p>Loan states: an approved offer puts the loan in RESTRUCTURE_PENDING until the borrower answers; declined,
 * expired or cancelled, it is overdue again. A conversion is refused while an extension is open, and an
 * extension while a conversion is open. Every transition takes the loan advisory lock and {@code @Version}.
 */
@Service
@RequiredArgsConstructor
public class EmiConversionService {

    static final String FLAG = "emi-conversion";
    private static final Set<String> REQUESTERS = Set.of("COLLECTION_EXECUTIVE", "COLLECTION_HEAD");
    private static final Set<String> REJECT_REASONS = Set.of("NOT_ELIGIBLE", "SETTLE_INSTEAD", "PAYMENT_HISTORY", "OTHER");
    private static final Set<String> DECLINE_REASONS = Set.of("NOT_NOW", "DID_NOT_ASK");
    private static final Set<LoanLifecycle> OVERDUE = EnumSet.of(LoanLifecycle.OVERDUE_D1, LoanLifecycle.OVERDUE_REVIEW);
    private static final Set<MandateDebit.Status> IN_FLIGHT =
            EnumSet.of(MandateDebit.Status.SCHEDULED, MandateDebit.Status.IN_PROGRESS);

    /** How EMI instalments are charged interest. Only NONE is defined: an instalment is principal + converted late charge. */
    public enum InterestMode { NONE }

    private final EmiConversionRepository emis;
    private final LoanRepository loans;
    private final PaymentRepository payments;
    private final MandateDebitRepository debits;
    private final PricingService pricing;
    private final RepaymentService repayments;
    private final RepaymentScheduleService schedules;
    private final LoanExtensionLedger extensionLedger;
    private final ExtensionDirectory extensions;
    private final SettlementDirectory settlements;
    private final LoanDirectory loanDirectory;
    private final FeatureFlagService flags;
    private final OtpVerifierPort otp;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final ComplaintService complaints;

    @Value("${navix.emi.interest-mode:NONE}")
    private InterestMode interestMode = InterestMode.NONE;

    /**
     * The instalments for {@code principal} + {@code charges} owed on {@code day0}: {@code n} instalments, the
     * k-th due {@code gap × k} days later, each part split evenly with the remainder paise on the last (CC-20).
     * Pure.
     */
    static List<Instalment> plan(LocalDate day0, long principal, long charges, int n, int gap, ProductSnapshot product) {
        List<Instalment> out = new ArrayList<>();
        for (int k = 1; k <= n; k++) {
            long p = k < n ? principal / n : principal - principal / n * (n - 1);
            long c = k < n ? charges / n : charges - charges / n * (n - 1);
            LocalDate due = day0.plusDays((long) gap * k);
            out.add(new Instalment(due, LoanMath.dueAt(due, product), p, c));
        }
        return out;
    }

    // ---- staff --------------------------------------------------------------------------

    /** The panel: eligibility with a reason code, the plan for accepting today, the open conversion and history. */
    @Transactional(readOnly = true)
    public EmiPanel panel(Long loanId) {
        requireStaffReader();
        Loan loan = requireLoan(loanId);
        List<EmiConversion> all = emis.findByLoanIdOrderByRequestedAtAsc(loanId);
        EmiConversion open = all.stream().filter(e -> EmiStatus.OPEN.contains(e.getStatus())).findFirst().orElse(null);
        String reason = open != null ? "EMI_ALREADY_OPEN" : ineligibleReason(loan);
        Owed owed = owedToday(loan);
        ProductSnapshot product = pricing.product(loan.getProductVersionId());
        List<Instalment> preview = plan(today(), owed.principal(), owed.charges(),
                product.emiInstalments(loan.getPrincipal()), product.emiGapDays(), product);
        return new EmiPanel(loanId, reason == null, reason, owed.total(), InstalmentView.preview(preview),
                open == null ? null : EmiView.of(open), all.stream().map(EmiView::of).toList());
    }

    /** ADMIN's queue: conversions waiting for a decision. */
    @Transactional(readOnly = true)
    public List<EmiView> list(EmiStatus status) {
        requireStaffReader();
        return emis.findByStatusOrderByRequestedAtAsc(status == null ? EmiStatus.REQUESTED : status).stream()
                .map(EmiView::of).toList();
    }

    @Transactional
    public EmiView request(Long loanId, EmiRequest request) {
        String role = ActorContext.get().role();
        if (!REQUESTERS.contains(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only collections can request an EMI conversion.");
        }
        if (!flags.isEnabled(FLAG, false)) {
            throw new BusinessException("FEATURE_DISABLED", "EMI conversion is switched off.");
        }
        extensionLedger.lock(loanId);
        Loan loan = requireLoan(loanId);
        emis.findFirstByLoanIdAndStatusIn(loanId, EmiStatus.OPEN).ifPresent(e -> {
            throw new ConflictException("EMI_ALREADY_OPEN", "An EMI conversion is already open on this loan.",
                    e.getId() + ":" + e.getStatus());
        });
        requireEligible(loan);
        ProductSnapshot product = pricing.product(loan.getProductVersionId());
        LocalDate today = today();
        LocalDate lastDay = today.plusDays(product.offerValidityDays());
        LocalDate acceptBy = request != null && request.acceptBy() != null ? request.acceptBy() : lastDay;
        if (acceptBy.isBefore(today) || acceptBy.isAfter(lastDay)) {
            throw new BusinessException("INVALID_ACCEPT_BY",
                    "Pick an accept-by date from today to " + product.offerValidityDays() + " days ahead.");
        }
        EmiConversion e = new EmiConversion();
        e.setLoanId(loanId);
        e.setInstalmentCount(product.emiInstalments(loan.getPrincipal()));
        e.setGapDays(product.emiGapDays());
        e.setAcceptBy(acceptBy);
        e.setExpiresAt(endOfDay(acceptBy));
        e.setNote(request == null ? null : blankToNull(request.note()));
        e.setRequestedBy(actorStaffId());
        e.setRequestedAt(clock.instant());
        try {
            return EmiView.of(emis.saveAndFlush(e));
        } catch (DataIntegrityViolationException race) {
            throw new ConflictException("EMI_ALREADY_OPEN", "An EMI conversion is already open on this loan.", null);
        }
    }

    /** ADMIN only, never the requester; the loan must still be eligible. The loan waits in RESTRUCTURE_PENDING. */
    @Transactional
    public EmiView approve(UUID id) {
        requireAdmin();
        EmiConversion e = lockAndLoad(id);
        requireStatus(e, EmiStatus.REQUESTED);
        requireNotExpired(e);
        long approver = actorStaffId();
        SeparationOfDutiesGuard.emiApprover(approver, e.getRequestedBy());
        if (!flags.isEnabled(FLAG, false)) {
            throw new BusinessException("FEATURE_DISABLED", "EMI conversion is switched off.");
        }
        Loan loan = requireLoan(e.getLoanId());
        requireEligible(loan);
        loan.advanceTo(extensionLedger.effectiveState(loan, pricing.product(loan.getProductVersionId())));
        loan.moveTo(LoanLifecycle.RESTRUCTURE_PENDING);
        loans.save(loan);
        e.moveTo(EmiStatus.APPROVED);
        e.setApprovedBy(approver);
        e.setApprovedAt(clock.instant());
        return EmiView.of(emis.saveAndFlush(e));
    }

    @Transactional
    public EmiView reject(UUID id, String reason, String note) {
        requireAdmin();
        if (reason == null || !REJECT_REASONS.contains(reason)) {
            throw new BusinessException("INVALID_REJECTION_REASON", "reason must be one of " + REJECT_REASONS);
        }
        EmiConversion e = lockAndLoad(id);
        requireStatus(e, EmiStatus.REQUESTED);
        long by = actorStaffId();
        SeparationOfDutiesGuard.emiApprover(by, e.getRequestedBy());
        e.moveTo(EmiStatus.REJECTED);
        e.setRejectedBy(by);
        e.setRejectedAt(clock.instant());
        e.setRejectReason(reason);
        e.setRejectNote(blankToNull(note));
        return EmiView.of(emis.saveAndFlush(e));
    }

    /** Collections or an ADMIN withdraws an offer the borrower has not accepted. */
    @Transactional
    public EmiView cancel(UUID id, String reason) {
        String role = ActorContext.get().role();
        if (!REQUESTERS.contains(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only collections or an ADMIN can cancel an EMI offer.");
        }
        EmiConversion e = lockAndLoad(id);
        boolean approved = e.getStatus() == EmiStatus.APPROVED;
        e.moveTo(EmiStatus.CANCELLED);
        e.setCancelledBy(actorStaffId());
        e.setCancelledAt(clock.instant());
        e.setCancelReason(reason == null || reason.isBlank() ? "STAFF" : reason.trim());
        if (approved) {
            backToOverdue(e.getLoanId());
        }
        return EmiView.of(emis.saveAndFlush(e));
    }

    /**
     * EXT-27 for EMI: a dispute cancels an open (REQUESTED or APPROVED) offer and an approved one's loan goes back
     * to the overdue state its dates give. The caller holds the loan lock. An ACTIVE conversion is never touched.
     *
     * @return true when an offer was cancelled
     */
    @Transactional
    public boolean cancelForDispute(Long loanId) {
        return emis.findFirstByLoanIdAndStatusIn(loanId, EmiStatus.OPEN).map(e -> {
            boolean approved = e.getStatus() == EmiStatus.APPROVED;
            e.moveTo(EmiStatus.CANCELLED);
            e.setCancelledAt(clock.instant());
            e.setCancelReason("DISPUTED");
            emis.saveAndFlush(e);
            if (approved) {
                backToOverdue(loanId);
            }
            return true;
        }).orElse(false);
    }

    // ---- borrower -----------------------------------------------------------------------

    /** The latest offer the borrower was shown (ADMIN-approved), or null. */
    @Transactional(readOnly = true)
    public BorrowerEmiView mine(Long loanId) {
        requireOwnLoan(loanId);
        return emis.findByLoanIdOrderByRequestedAtAsc(loanId).stream()
                .filter(e -> e.getApprovedAt() != null)
                .max(Comparator.comparing(EmiConversion::getRequestedAt))
                .map(this::borrowerView).orElse(null);
    }

    /** Send the confirmation OTP to the mobile on file (resolved here, never from the request). */
    @Transactional(readOnly = true)
    public OtpSentView requestOtp(Long loanId, UUID id) {
        EmiConversion e = borrowerEmi(loanId, id);
        requireStatus(e, EmiStatus.APPROVED);
        requireNotExpired(e);
        OtpVerifierPort.OtpRequestResult r = otp.request(mobileOf(loanId), OtpVerifierPort.EMI_CONVERSION);
        return new OtpSentView(r.sent(), r.ttlSeconds(), r.devCode());
    }

    /**
     * Accept with the OTP: this activates the conversion. Idempotent: once active, a retry returns it without a
     * code; the activation itself runs once per conversion through {@link IdempotencyService}.
     */
    @Transactional
    public BorrowerEmiView accept(Long loanId, UUID id, AcceptRequest request) {
        EmiConversion e = borrowerEmi(loanId, id);
        if (e.getStatus() == EmiStatus.ACTIVE) {
            return borrowerView(e);
        }
        extensionLedger.lock(loanId);
        requireVersion(e, request == null ? null : request.version());
        requireStatus(e, EmiStatus.APPROVED);
        requireNotExpired(e);
        if (request == null || request.consentVersion() == null || request.consentVersion().isBlank()) {
            throw new BusinessException("CONSENT_REQUIRED", "Confirm that you have read the EMI terms.");
        }
        if (!otp.verify(mobileOf(loanId), request.otp(), OtpVerifierPort.EMI_CONVERSION)) {
            throw new BusinessException("INVALID_OTP", "Invalid or expired OTP");
        }
        String consent = request.consentVersion().trim();
        idempotency.execute("EMI_ACTIVATE", "emi:" + e.getId(), e.getId().toString(),
                () -> activate(e, consent).longValue());
        return borrowerView(e);
    }

    /** "Not now" or "I didn't ask". The loan is overdue again. */
    @Transactional
    public BorrowerEmiView decline(Long loanId, UUID id, String reason, Long version) {
        if (reason == null || !DECLINE_REASONS.contains(reason)) {
            throw new BusinessException("INVALID_REASON", "reason must be one of " + DECLINE_REASONS);
        }
        EmiConversion e = borrowerEmi(loanId, id);
        extensionLedger.lock(loanId);
        requireVersion(e, version);
        e.moveTo(EmiStatus.DECLINED);
        e.setDeclinedAt(clock.instant());
        e.setDeclinedReason(reason);
        backToOverdue(loanId);
        if ("DID_NOT_ASK".equals(reason)) {
            // CC-71, as for an extension: "I didn't ask" opens a complaint ticket.
            complaints.openSystem(requireLoan(loanId).getCustomerId(), loanId,
                    "Borrower said they did not ask for the EMI offer on this loan (EMI " + e.getId() + ").");
        }
        return borrowerView(emis.saveAndFlush(e));
    }

    // ---- expiry -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<UUID> expirable() {
        return emis.findExpirable(EmiStatus.OPEN, clock.instant());
    }

    /** Expire one offer past its accept-by day, under the loan lock. Idempotent: false when it already moved on. */
    @Transactional
    public boolean expire(UUID id) {
        EmiConversion e = lockAndLoad(id);
        if (!e.getStatus().canMoveTo(EmiStatus.EXPIRED) || !clock.instant().isAfter(e.getExpiresAt())) {
            return false;
        }
        boolean approved = e.getStatus() == EmiStatus.APPROVED;
        e.moveTo(EmiStatus.EXPIRED);
        e.setExpiredAt(clock.instant());
        emis.saveAndFlush(e);
        if (approved) {
            backToOverdue(e.getLoanId());
        }
        return true;
    }

    // ---- activation ---------------------------------------------------------------------

    /**
     * Split what is owed today into the EMI schedule. Under the loan lock (the caller's): the money columns,
     * the loan RESTRUCTURED with its late charge frozen, the new schedule version, the balance_adjustment row.
     *
     * @return the new schedule version
     */
    private Integer activate(EmiConversion e, String consentVersion) {
        Loan loan = requireLoan(e.getLoanId());
        if (extensions.hasOpen(loan.getId())) {
            throw new BusinessException("EXTENSION_OPEN", "An extension is open on this loan.");
        }
        Instant now = clock.instant();
        LocalDate day0 = today();
        Owed owed = owedToday(loan);
        if (owed.total() <= 0) {
            throw new BusinessException("NOTHING_OWED", "Nothing is owed on this loan.");
        }
        ProductSnapshot product = pricing.product(loan.getProductVersionId());
        List<Instalment> plan = plan(day0, owed.principal(), owed.charges(), e.getInstalmentCount(), e.getGapDays(),
                product);
        Map<String, Object> before = snapshot(loan);
        before.put("outstandingPaise", owed.total());

        // The late charge to date is now part of the instalments; none accrues after this (emi.penal off).
        RepaymentService.OutstandingBreakdown b = repayments.outstandingBreakdownAsOf(loan, day0);
        loan.setPenalCarriedPaise(b.penaltyPaise());
        loan.setPenalDaysUsed(loan.getPenalDaysUsed() + b.penaltyDays());
        loan.setRestructuredAt(now);
        loan.moveTo(LoanLifecycle.RESTRUCTURED);
        loans.save(loan);
        int[] versions = schedules.writeEmi(loan, plan);

        Map<String, Object> after = snapshot(loan);
        after.put("emiId", e.getId().toString());
        after.put("instalments", plan.stream().map(i -> Map.of("dueDate", i.dueDate().toString(),
                "principalPaise", i.principalPaise(), "chargesPaise", i.penalPaise())).toList());
        jdbc.update("insert into balance_adjustment (loan_id, customer_id, kind, before, after, schedule_version_from,"
                        + " schedule_version_to, calc_version, approver_id, reason_code, acceptance_evidence, at)"
                        + " values (?, ?, 'EMI', cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?, 'EMI', cast(? as jsonb), ?)",
                loan.getId(), loan.getCustomerId(), toJson(before), toJson(after), versions[0] == 0 ? null : versions[0],
                versions[1], loan.getPricingVersionId(), e.getApprovedBy(),
                toJson(Map.of("acceptedAt", now.toString(), "consentVersion", consentVersion)), Timestamp.from(now));

        e.moveTo(EmiStatus.ACTIVE);
        e.setAcceptedAt(now);
        e.setConsentVersion(consentVersion);
        e.setConversionDate(day0);
        e.setPrincipalPaise(owed.principal());
        e.setChargesPaise(owed.charges());
        e.setInterestMode(interestMode.name());
        e.setScheduleVersion(versions[1]);
        e.setActivatedAt(now);
        emis.saveAndFlush(e);
        return versions[1];
    }

    // ---- helpers ------------------------------------------------------------------------

    /** What is owed today, split into the principal still open and everything else (late charge). */
    record Owed(long principal, long charges) {
        long total() {
            return principal + charges;
        }
    }

    private Owed owedToday(Loan loan) {
        long total = repayments.outstandingBreakdownAsOf(loan, today()).outstandingPaise();
        Long principalPaid = jdbc.queryForObject("select coalesce(sum(a.amount_paise), 0) from payment_allocation a"
                + " join payment p on p.id = a.payment_id where a.loan_id = ? and a.component = 'PRINCIPAL'"
                + " and p.purpose = 'REPAYMENT'", Long.class, loan.getId());
        long principal = Math.min(total, Math.max(0L, loan.getPrincipal() - (principalPaid == null ? 0L : principalPaid)));
        return new Owed(principal, total - principal);
    }

    /** Null when a conversion may be requested (or approved) now, else the reason code. */
    String ineligibleReason(Loan loan) {
        if (!flags.isEnabled(FLAG, false)) {
            return "FEATURE_DISABLED";
        }
        LoanLifecycle state = extensionLedger.effectiveState(loan, pricing.product(loan.getProductVersionId()));
        if (state == LoanLifecycle.CLOSED) {
            return "LOAN_CLOSED";
        }
        if (state == LoanLifecycle.RESTRUCTURE_PENDING || state == LoanLifecycle.RESTRUCTURED) {
            return "RESTRUCTURED";
        }
        if (!OVERDUE.contains(state)) {
            return "NOT_OVERDUE";
        }
        if (loan.getHoldStatus() != LoanHold.NONE) {
            return "ON_HOLD";
        }
        if (payments.findByLoanId(loan.getId()).stream()
                .anyMatch(p -> !p.isExtension() && p.getStatus() == PaymentStatus.PENDING_VERIFICATION)) {
            return "REPAYMENT_PENDING";
        }
        if (debits.existsByLoanIdAndStatusIn(loan.getId(), IN_FLIGHT)) {
            return "DEBIT_IN_FLIGHT";
        }
        if (extensions.hasOpen(loan.getId())) {
            return "EXTENSION_OPEN";
        }
        if (settlements.approvedSettlementAmount(loan.getId()).isPresent()) {
            return "SETTLEMENT_OPEN";
        }
        return null;
    }

    private void requireEligible(Loan loan) {
        String reason = ineligibleReason(loan);
        if (reason != null) {
            throw new BusinessException(reason, switch (reason) {
                case "FEATURE_DISABLED" -> "EMI conversion is switched off.";
                case "LOAN_CLOSED" -> "This loan is closed.";
                case "RESTRUCTURED" -> "This loan is already being restructured.";
                case "NOT_OVERDUE" -> "An EMI conversion is only for an overdue loan.";
                case "ON_HOLD" -> "This loan is on hold.";
                case "REPAYMENT_PENDING" -> "A repayment is waiting for the Accountant.";
                case "DEBIT_IN_FLIGHT" -> "An auto-debit is in progress on this loan.";
                case "EXTENSION_OPEN" -> "An extension is open on this loan. Decide it first.";
                case "SETTLEMENT_OPEN" -> "This loan has an approved settlement.";
                default -> "This loan cannot be converted now.";
            });
        }
    }

    /** RESTRUCTURE_PENDING → the overdue state the clock gives (the due date did not move). */
    private void backToOverdue(Long loanId) {
        Loan loan = requireLoan(loanId);
        if (loan.getLifecycleStatus() != LoanLifecycle.RESTRUCTURE_PENDING) {
            return;
        }
        ProductSnapshot product = pricing.product(loan.getProductVersionId());
        Instant dueAt = loan.getDueAt() != null ? loan.getDueAt() : LoanMath.dueAt(loan.getDueDate(), product);
        LoanLifecycle target = LoanLifecycle.forTime(clock.instant(), dueAt, product.zone(), product.overdueReviewDpd());
        loan.moveTo(OVERDUE.contains(target) ? target : LoanLifecycle.OVERDUE_D1);
        loans.save(loan);
    }

    private BorrowerEmiView borrowerView(EmiConversion e) {
        List<InstalmentView> instalments;
        if (e.getStatus() == EmiStatus.ACTIVE) {
            instalments = jdbc.query("""
                    select i.seq, i.due_date, i.due_at, i.principal_paise, i.penal_paise, i.paid_paise, i.status
                    from schedule_instalment i join repayment_schedule s on s.id = i.schedule_id
                    where s.loan_id = ? and s.version = ? order by i.seq
                    """, (rs, n) -> new InstalmentView(rs.getInt(1), rs.getObject(2, LocalDate.class),
                    rs.getTimestamp(3).toInstant(), rs.getLong(4), rs.getLong(5), rs.getLong(4) + rs.getLong(5),
                    rs.getLong(6), rs.getString(7)), e.getLoanId(), e.getScheduleVersion());
        } else if (e.getStatus() == EmiStatus.APPROVED) {
            Loan loan = requireLoan(e.getLoanId());
            Owed owed = owedToday(loan);
            instalments = InstalmentView.preview(plan(today(), owed.principal(), owed.charges(),
                    e.getInstalmentCount(), e.getGapDays(), pricing.product(loan.getProductVersionId())));
        } else {
            instalments = List.of();
        }
        long total = instalments.stream().mapToLong(InstalmentView::totalPaise).sum();
        return new BorrowerEmiView(e.getId(), e.getLoanId(), e.getStatus().name(), e.getVersion(), e.getAcceptBy(),
                e.getExpiresAt(), e.getInstalmentCount(), total, instalments,
                e.getInterestMode() != null ? e.getInterestMode() : interestMode.name(), e.getAcceptedAt(),
                e.getDeclinedAt(), e.getActivatedAt());
    }

    private EmiConversion lockAndLoad(UUID id) {
        EmiConversion peek = require(id);
        extensionLedger.lock(peek.getLoanId());
        return peek;
    }

    private void requireOwnLoan(Long loanId) {
        var actor = ActorContext.get();
        if (actor == null || !"BORROWER".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only the borrower can do this.");
        }
        if (!String.valueOf(requireLoan(loanId).getCustomerId()).equals(actor.id())) {
            throw new BusinessException("FORBIDDEN_OWNER", "Not your loan");
        }
    }

    private EmiConversion borrowerEmi(Long loanId, UUID id) {
        requireOwnLoan(loanId);
        EmiConversion e = require(id);
        if (!e.getLoanId().equals(loanId) || e.getApprovedAt() == null) {
            throw new ResourceNotFoundException("EmiConversion", String.valueOf(id));
        }
        return e;
    }

    private String mobileOf(Long loanId) {
        String mobile = loanDirectory.findLoan(loanId).map(LoanSummary::mobile).orElse(null);
        if (mobile == null || mobile.isBlank()) {
            throw new BusinessException("MOBILE_MISSING", "No mobile on file for this loan.");
        }
        return mobile;
    }

    private void requireNotExpired(EmiConversion e) {
        if (clock.instant().isAfter(e.getExpiresAt())) {
            throw new ConflictException("EMI_EXPIRED", "This EMI offer has expired.", "EXPIRED");
        }
    }

    private static void requireStatus(EmiConversion e, EmiStatus expected) {
        if (e.getStatus() != expected) {
            throw new ConflictException("STALE_STATE", "This EMI offer is " + e.getStatus().name().toLowerCase() + ".",
                    e.getStatus().name());
        }
    }

    /** A borrower action carries the version the screen showed; a stale one is 409. */
    private static void requireVersion(EmiConversion e, Long version) {
        if (version != null && version != e.getVersion()) {
            throw new ConflictException("STALE_STATE", "This EMI offer changed. Refresh to see where it stands.",
                    e.getStatus().name());
        }
    }

    private EmiConversion require(UUID id) {
        return emis.findById(id).orElseThrow(() -> new ResourceNotFoundException("EmiConversion", String.valueOf(id)));
    }

    private Loan requireLoan(Long loanId) {
        return loans.findById(loanId).orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
    }

    private Instant endOfDay(LocalDate day) {
        return day.atTime(LocalTime.of(23, 59, 59)).atZone(clock.getZone()).toInstant();
    }

    private static Map<String, Object> snapshot(Loan loan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dueDate", String.valueOf(loan.getDueDate()));
        m.put("lifecycleStatus", loan.getLifecycleStatus().name());
        m.put("principalPaise", loan.getPrincipal());
        m.put("penalDaysUsed", loan.getPenalDaysUsed());
        m.put("penalCarriedPaise", loan.getPenalCarriedPaise());
        return m;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return json.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void requireStaffReader() {
        String role = ActorContext.get().role();
        if (role == null || "BORROWER".equals(role) || "ANONYMOUS".equals(role) || "DSA".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Staff role required");
        }
    }

    /** RBAC server-side: only an ADMIN approves or rejects a conversion (422 for anyone else). */
    private static void requireAdmin() {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only an ADMIN can approve or reject an EMI conversion.");
        }
    }

    private static long actorStaffId() {
        try {
            return Long.parseLong(ActorContext.get().id());
        } catch (NumberFormatException ex) {
            throw new BusinessException("ACTOR_NOT_STAFF", "The acting identity is not a real staff id; sign in as staff");
        }
    }
}
