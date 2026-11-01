package com.navix.collections.service;

import com.navix.collections.dto.ExtensionDtos.AcceptRequest;
import com.navix.collections.dto.ExtensionDtos.BorrowerExtensionView;
import com.navix.collections.dto.ExtensionDtos.ExtensionPanel;
import com.navix.collections.dto.ExtensionDtos.ExtensionView;
import com.navix.collections.dto.ExtensionDtos.OfferRequest;
import com.navix.collections.dto.ExtensionDtos.OtpSentView;
import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.ExtensionStatus;
import com.navix.collections.entity.LoanExtension;
import com.navix.collections.entity.SettlementStatus;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.LoanExtensionRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ConflictException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.extension.ExtensionFacts;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.notification.event.ExtensionApprovedEvent;
import com.navix.common.notification.event.ExtensionDeclinedEvent;
import com.navix.common.notification.event.ExtensionExpiredEvent;
import com.navix.common.notification.event.ExtensionOfferedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.SeparationOfDutiesGuard;
import com.navix.common.verification.OtpVerifierPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collections-led loan extension (PLAN Phase 6b): an officer offers after a borrower call, the Collection Head
 * approves, the borrower confirms with an OTP in the app and pays (or the officer logs the payment), and the
 * Accountant's verification moves the due date ({@code LoanDirectory.applyExtension} / the loan module).
 *
 * <p>Every transition takes the loan advisory lock, checks {@link ExtensionStatus} and relies on
 * {@code @Version}: an illegal or stale move is 409 STALE_STATE with the current state.
 */
@Service
@RequiredArgsConstructor
public class LoanExtensionService {

    static final String FLAG = "loan-extension";
    private static final String OFFICER_ROLE = "COLLECTION_EXECUTIVE";
    private static final String HEAD_ROLE = "COLLECTION_HEAD";
    private static final Set<String> REJECT_REASONS = Set.of("NOT_ELIGIBLE", "SETTLE_INSTEAD", "PAYMENT_HISTORY", "OTHER");
    private static final Set<String> DECLINE_REASONS = Set.of("NOT_NOW", "DID_NOT_ASK");
    private static final int RESENDS_PER_DAY = 3;

    private final LoanExtensionRepository extensions;
    private final CollectionCaseRepository cases;
    private final SettlementRepository settlements;
    private final CollectionsService collectionsService;
    private final LoanDirectory loans;
    private final OtpVerifierPort otp;
    private final FeatureFlagService flags;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ---- staff --------------------------------------------------------------------------

    /** The panel: eligibility with a reason code, the cap, today's quote, the open extension and history. */
    @Transactional(readOnly = true)
    public ExtensionPanel panel(UUID caseId) {
        requireStaffReader();
        collectionsService.assertCaseAccessible(caseId);
        CollectionCase c = requireCase(caseId);
        ExtensionFacts facts = loans.extensionFacts(c.getLoanId(), today());
        List<LoanExtension> all = extensions.findByLoanIdOrderBySeqAsc(c.getLoanId());
        LoanExtension open = all.stream().filter(e -> e.getStatus().isOpen()).findFirst().orElse(null);
        String reason = open != null ? "EXTENSION_ALREADY_OPEN"
                : facts.ineligibleReason() != null ? facts.ineligibleReason()
                : settlementOpen(caseId) ? "SETTLEMENT_OPEN"
                : !flags.isEnabled(FLAG, false) ? "FEATURE_DISABLED" : null;
        return new ExtensionPanel(caseId, c.getLoanId(), reason == null, reason, facts.cap(), facts.extensionCount(),
                facts.quote(), facts.dueDate(), facts.originalDueDate(), facts.newDueDate(),
                open == null ? null : ExtensionView.of(open), all.stream().map(ExtensionView::of).toList());
    }

    /** Offer on a loan that may have no case yet: the case is opened first (idempotent). */
    @Transactional
    public ExtensionView offerForLoan(Long loanId, OfferRequest request) {
        requireOneOf(OFFICER_ROLE, HEAD_ROLE);
        return offer(collectionsService.openCase(loanId).id(), request);
    }

    @Transactional
    public ExtensionView offer(UUID caseId, OfferRequest request) {
        requireOneOf(OFFICER_ROLE, HEAD_ROLE);
        if (!flags.isEnabled(FLAG, false)) {
            throw new BusinessException("FEATURE_DISABLED", "Loan extensions are switched off.");
        }
        collectionsService.assertCaseAccessible(caseId);
        CollectionCase c = requireCase(caseId);
        Long loanId = c.getLoanId();
        loans.lockLoan(loanId);
        extensions.findFirstByLoanIdAndStatusIn(loanId, ExtensionStatus.OPEN).ifPresent(e -> {
            throw new ConflictException("EXTENSION_ALREADY_OPEN",
                    "An extension is already open on this loan (" + e.getStatus().name().toLowerCase() + ").",
                    e.getId() + ":" + e.getStatus());
        });
        LocalDate today = today();
        ExtensionFacts facts = loans.extensionFacts(loanId, today);
        String override = request == null ? null : blankToNull(request.overrideReason());
        if (facts.ineligibleReason() != null) {
            boolean adminOverride = "EXTENSION_CAP_REACHED".equals(facts.ineligibleReason())
                    && override != null && "ADMIN".equals(ActorContext.get().role());
            if (!adminOverride) {
                throw new BusinessException(facts.ineligibleReason(), ineligibleMessage(facts.ineligibleReason()));
            }
        } else {
            override = null;
        }
        if (settlementOpen(caseId)) {
            throw new BusinessException("SETTLEMENT_OPEN", "A settlement is open on this case. Decide it first.");
        }
        LocalDate payBy = request != null && request.payBy() != null ? request.payBy() : today;
        if (payBy.isBefore(today) || !payBy.isBefore(facts.newDueDate())) {
            throw new BusinessException("INVALID_PAY_BY", "Pick a pay-by date from today and before the new due date.");
        }
        LoanExtension e = new LoanExtension();
        e.setLoanId(loanId);
        e.setCollectionCaseId(caseId);
        e.setSeq(extensions.maxSeq(loanId) + 1);
        e.setFeePaise(facts.quote().feePaise());
        e.setGstPaise(facts.quote().gstPaise());
        e.setInterestPaise(facts.quote().interestPaise());
        e.setPayBy(payBy);
        e.setExpiresAt(payBy.atTime(LocalTime.of(23, 59, 59)).atZone(clock.getZone()).toInstant());
        e.setOldDueDate(facts.dueDate());
        e.setNewDueDate(facts.newDueDate());
        e.setCallNote(request == null ? null : blankToNull(request.callNote()));
        e.setRecordingId(request == null ? null : blankToNull(request.recordingId()));
        e.setOverrideReason(override);
        e.setProposedBy(actorStaffId());
        e.setProposedAt(clock.instant());
        try {
            e = extensions.saveAndFlush(e);
        } catch (DataIntegrityViolationException race) {
            // EXT-01: a second officer offered at the same moment; the partial unique index decided.
            throw new ConflictException("EXTENSION_ALREADY_OPEN", "An extension is already open on this loan.", null);
        }
        events.publishEvent(new ExtensionOfferedEvent(e.getId(), caseId, loanId, facts.customerId(), e.getSeq(),
                facts.quote().totalPaise(), e.getProposedBy(), clock.instant()));
        return ExtensionView.of(e);
    }

    /** Collection Head approves: not their own offer (SoD), not past pay-by (EXT-05), the loan still eligible. */
    @Transactional
    public ExtensionView approve(UUID extensionId) {
        requireOneOf(HEAD_ROLE);
        LoanExtension e = lockAndLoad(extensionId);
        requireStatus(e, ExtensionStatus.OFFERED);
        if (clock.instant().isAfter(e.getExpiresAt())) {
            throw new BusinessException("EXTENSION_EXPIRED", "The pay-by day has passed. Ask the officer to offer again.");
        }
        long approver = actorStaffId();
        SeparationOfDutiesGuard.extensionApprover(approver, e.getProposedBy());
        ExtensionFacts facts = loans.extensionFacts(e.getLoanId(), today());
        String reason = facts.ineligibleReason();
        if (reason != null && !("EXTENSION_CAP_REACHED".equals(reason) && e.getOverrideReason() != null)) {
            throw new BusinessException(reason, ineligibleMessage(reason));
        }
        e.moveTo(ExtensionStatus.APPROVED);
        e.setApprovedBy(approver);
        e.setApprovedAt(clock.instant());
        e = extensions.saveAndFlush(e);
        publishApproved(e, facts);
        return ExtensionView.of(e);
    }

    @Transactional
    public ExtensionView reject(UUID extensionId, String reason, String note) {
        requireOneOf(HEAD_ROLE);
        if (reason == null || !REJECT_REASONS.contains(reason)) {
            throw new BusinessException("INVALID_REJECTION_REASON", "reason must be one of " + REJECT_REASONS);
        }
        LoanExtension e = lockAndLoad(extensionId);
        requireStatus(e, ExtensionStatus.OFFERED);
        long by = actorStaffId();
        SeparationOfDutiesGuard.extensionApprover(by, e.getProposedBy());
        e.moveTo(ExtensionStatus.REJECTED);
        e.setRejectedBy(by);
        e.setRejectedAt(clock.instant());
        e.setRejectReason(reason);
        e.setRejectNote(blankToNull(note));
        return ExtensionView.of(extensions.saveAndFlush(e));
    }

    /** The officer withdraws an offer the borrower has not paid for yet. */
    @Transactional
    public ExtensionView cancel(UUID extensionId, String reason) {
        requireOneOf(OFFICER_ROLE, HEAD_ROLE);
        LoanExtension e = lockAndLoad(extensionId);
        collectionsService.assertCaseAccessible(e.getCollectionCaseId());
        if (e.getStatus() == ExtensionStatus.PAYMENT_PENDING) {
            throw new ConflictException("STALE_STATE", "A payment for this extension is waiting for the Accountant.",
                    e.getStatus().name());
        }
        e.moveTo(ExtensionStatus.CANCELLED);
        e.setCancelledBy(actorStaffId());
        e.setCancelledAt(clock.instant());
        e.setCancelReason(reason == null || reason.isBlank() ? "OFFICER" : reason.trim());
        return ExtensionView.of(extensions.saveAndFlush(e));
    }

    /** Re-send the borrower notification, at most {@value #RESENDS_PER_DAY} times a day (EXT-07). */
    @Transactional
    public ExtensionView resend(UUID extensionId) {
        requireOneOf(OFFICER_ROLE, HEAD_ROLE);
        LoanExtension e = lockAndLoad(extensionId);
        collectionsService.assertCaseAccessible(e.getCollectionCaseId());
        if (e.getStatus() != ExtensionStatus.APPROVED && e.getStatus() != ExtensionStatus.ACCEPTED) {
            throw new ConflictException("STALE_STATE", "Only an offer waiting for the borrower can be re-sent.",
                    e.getStatus().name());
        }
        LocalDate today = today();
        int sent = today.equals(e.getResendDay()) ? e.getResendCount() : 0;
        if (sent >= RESENDS_PER_DAY) {
            throw new BusinessException("RESEND_LIMIT", "Already re-sent " + RESENDS_PER_DAY + " times today.");
        }
        e.setResendDay(today);
        e.setResendCount(sent + 1);
        e = extensions.saveAndFlush(e);
        publishApproved(e, loans.extensionFacts(e.getLoanId(), today));
        return ExtensionView.of(e);
    }

    // ---- borrower -----------------------------------------------------------------------

    /** The borrower opened the offer; shown to the officer ("not opened yet" until then, EXT-07). */
    @Transactional
    public BorrowerExtensionView seen(Long loanId, UUID extensionId) {
        LoanExtension e = borrowerExtension(loanId, extensionId);
        if (e.getSeenByBorrowerAt() == null) {
            e.setSeenByBorrowerAt(clock.instant());
            e = extensions.saveAndFlush(e);
        }
        return BorrowerExtensionView.of(e);
    }

    /** Send the confirmation OTP to the mobile on file (resolved here, never from the request). */
    @Transactional(readOnly = true)
    public OtpSentView requestOtp(Long loanId, UUID extensionId) {
        LoanExtension e = borrowerExtension(loanId, extensionId);
        requireStatus(e, ExtensionStatus.APPROVED);
        requireNotExpired(e);
        OtpVerifierPort.OtpRequestResult r = otp.request(mobileOf(loanId), OtpVerifierPort.LOAN_EXTENSION);
        return new OtpSentView(r.sent(), r.ttlSeconds(), r.devCode());
    }

    /**
     * Confirm with the OTP. Idempotent: once accepted, a retry (the app was killed after the OTP verified,
     * EXT-12) returns the extension as it now stands without asking for a code again.
     */
    @Transactional
    public BorrowerExtensionView accept(Long loanId, UUID extensionId, AcceptRequest request) {
        LoanExtension e = borrowerExtension(loanId, extensionId);
        if (e.getAcceptedAt() != null) {
            return BorrowerExtensionView.of(e);
        }
        loans.lockLoan(loanId);
        requireVersion(e, request == null ? null : request.version());
        requireStatus(e, ExtensionStatus.APPROVED);
        requireNotExpired(e);
        if (request == null || request.consentVersion() == null || request.consentVersion().isBlank()) {
            throw new BusinessException("CONSENT_REQUIRED", "Confirm that you have read the extension terms.");
        }
        if (!otp.verify(mobileOf(loanId), request.otp(), OtpVerifierPort.LOAN_EXTENSION)) {
            throw new BusinessException("INVALID_OTP", "Invalid or expired OTP");
        }
        e.moveTo(ExtensionStatus.ACCEPTED);
        e.setAcceptedAt(clock.instant());
        e.setConsentVersion(request.consentVersion().trim());
        return BorrowerExtensionView.of(extensions.saveAndFlush(e));
    }

    /**
     * "Not now" or "I didn't ask" (EXT-13). The officer is told. DID_NOT_ASK is also the borrower's complaint
     * (CC-71); the complaint ticket itself arrives with Phase 7.
     */
    @Transactional
    public BorrowerExtensionView decline(Long loanId, UUID extensionId, String reason, Long version) {
        if (reason == null || !DECLINE_REASONS.contains(reason)) {
            throw new BusinessException("INVALID_REASON", "reason must be one of " + DECLINE_REASONS);
        }
        LoanExtension e = borrowerExtension(loanId, extensionId);
        loans.lockLoan(loanId);
        requireVersion(e, version);
        e.moveTo(ExtensionStatus.DECLINED);
        e.setDeclinedAt(clock.instant());
        e.setDeclinedReason(reason);
        e = extensions.saveAndFlush(e);
        events.publishEvent(new ExtensionDeclinedEvent(e.getId(), e.getCollectionCaseId(), loanId,
                Long.valueOf(ActorContext.get().id()), officerOf(e), reason, clock.instant()));
        return BorrowerExtensionView.of(e);
    }

    // ---- expiry sweep -------------------------------------------------------------------

    /** Offers past their pay-by still waiting on the Head or the borrower (PAYMENT_PENDING is never swept). */
    @Transactional(readOnly = true)
    public List<UUID> expirable() {
        return extensions.findExpirable(Set.of(ExtensionStatus.OFFERED, ExtensionStatus.APPROVED,
                ExtensionStatus.ACCEPTED), clock.instant());
    }

    /** Expire one offer, under the loan lock. Idempotent: false when it already moved on. */
    @Transactional
    public boolean expire(UUID extensionId) {
        LoanExtension e = lockAndLoad(extensionId);
        if (!e.getStatus().canMoveTo(ExtensionStatus.EXPIRED) || !clock.instant().isAfter(e.getExpiresAt())) {
            return false;
        }
        e.moveTo(ExtensionStatus.EXPIRED);
        e.setExpiredAt(clock.instant());
        e = extensions.saveAndFlush(e);
        Long customerId = loans.findLoan(e.getLoanId()).map(LoanSummary::customerId).orElse(null);
        events.publishEvent(new ExtensionExpiredEvent(e.getId(), e.getCollectionCaseId(), e.getLoanId(), customerId,
                officerOf(e), clock.instant()));
        return true;
    }

    // ---- helpers ------------------------------------------------------------------------

    private void publishApproved(LoanExtension e, ExtensionFacts facts) {
        events.publishEvent(new ExtensionApprovedEvent(e.getId(), e.getCollectionCaseId(), e.getLoanId(),
                facts.customerId(), facts.quote().totalPaise(), e.getPayBy(), e.getNewDueDate(), clock.instant()));
    }

    private boolean settlementOpen(UUID caseId) {
        return settlements.findByCollectionCaseId(caseId).stream()
                .anyMatch(s -> s.getStatus() == null || s.getStatus() == SettlementStatus.PROPOSED
                        || s.getStatus() == SettlementStatus.APPROVED);
    }

    /** The assigned officer, else the proposer: who hears about a decline or an expiry. */
    private Long officerOf(LoanExtension e) {
        Long assigned = cases.findById(e.getCollectionCaseId()).map(CollectionCase::getAssignedOfficerId).orElse(null);
        return assigned != null ? assigned : e.getProposedBy();
    }

    private LoanExtension lockAndLoad(UUID extensionId) {
        LoanExtension peek = require(extensionId);
        loans.lockLoan(peek.getLoanId());
        return peek;
    }

    private LoanExtension borrowerExtension(Long loanId, UUID extensionId) {
        var actor = ActorContext.get();
        if (actor == null || !"BORROWER".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only the borrower can do this.");
        }
        LoanSummary loan = loans.findLoan(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
        if (!String.valueOf(loan.customerId()).equals(actor.id())) {
            throw new BusinessException("FORBIDDEN_OWNER", "Not your loan");
        }
        LoanExtension e = require(extensionId);
        if (!e.getLoanId().equals(loanId) || e.getApprovedAt() == null) {
            throw new ResourceNotFoundException("LoanExtension", String.valueOf(extensionId));
        }
        return e;
    }

    private String mobileOf(Long loanId) {
        String mobile = loans.findLoan(loanId).map(LoanSummary::mobile).orElse(null);
        if (mobile == null || mobile.isBlank()) {
            throw new BusinessException("MOBILE_MISSING", "No mobile on file for this loan.");
        }
        return mobile;
    }

    private void requireNotExpired(LoanExtension e) {
        if (clock.instant().isAfter(e.getExpiresAt())) {
            throw new ConflictException("EXTENSION_EXPIRED", "This extension offer has expired.", "EXPIRED");
        }
    }

    private static void requireStatus(LoanExtension e, ExtensionStatus expected) {
        if (e.getStatus() != expected) {
            throw new ConflictException("STALE_STATE", "This extension is " + e.getStatus().name().toLowerCase() + ".",
                    e.getStatus().name());
        }
    }

    /** EXT-09: a money action carries the version the screen showed; a stale one is 409. */
    private static void requireVersion(LoanExtension e, Long version) {
        if (version != null && version != e.getVersion()) {
            throw new ConflictException("STALE_STATE", "This extension changed. Refresh to see where it stands.",
                    e.getStatus().name());
        }
    }

    private LoanExtension require(UUID id) {
        return extensions.findById(id).orElseThrow(() -> new ResourceNotFoundException("LoanExtension", String.valueOf(id)));
    }

    private CollectionCase requireCase(UUID caseId) {
        return cases.findById(caseId).orElseThrow(() -> new ResourceNotFoundException("CollectionCase", String.valueOf(caseId)));
    }

    private static String ineligibleMessage(String reason) {
        return switch (reason) {
            case "NOT_DUE_YET" -> "An extension can be offered from the due date.";
            case "LOAN_CLOSED" -> "This loan is closed.";
            case "ON_HOLD" -> "This loan is on hold.";
            case "REPAYMENT_PENDING" -> "A repayment is waiting for the Accountant.";
            case "DEBIT_IN_FLIGHT" -> "An auto-debit is in progress on this loan.";
            case "EXTENSION_CAP_REACHED" -> "The extension cap is reached: repay, settle or ask an ADMIN to override.";
            case "RESTRUCTURED" -> "This loan is being restructured.";
            case "EMI_OPEN" -> "An EMI conversion is open on this loan. Decide it first.";
            default -> "This loan cannot be extended now.";
        };
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

    private static void requireOneOf(String... roles) {
        String role = ActorContext.get().role();
        if ("ADMIN".equals(role)) {
            return;
        }
        for (String r : roles) {
            if (r.equals(role)) {
                return;
            }
        }
        throw new BusinessException("FORBIDDEN_ROLE", "This action requires one of: " + String.join(", ", roles));
    }

    private static long actorStaffId() {
        try {
            return Long.parseLong(ActorContext.get().id());
        } catch (NumberFormatException e) {
            throw new BusinessException("ACTOR_NOT_STAFF", "The acting identity is not a real staff id; sign in as staff");
        }
    }
}
