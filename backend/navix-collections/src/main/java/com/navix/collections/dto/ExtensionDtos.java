package com.navix.collections.dto;

import com.navix.collections.entity.LoanExtension;
import com.navix.common.extension.ExtensionQuote;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for loan extensions (PLAN Phase 6b). Money is integer paise. */
public final class ExtensionDtos {

    private ExtensionDtos() {
    }

    /**
     * An officer's offer after a call. {@code payBy} defaults to today (IST); the offer expires at 23:59:59 IST
     * on it. {@code overrideReason} lets an ADMIN offer past the cap (EXT-25).
     */
    public record OfferRequest(LocalDate payBy, String callNote, String recordingId, String overrideReason) {
    }

    public record ReasonRequest(String reason, String note) {
    }

    /** Borrower accept: the OTP sent to the mobile on file, the consent text version shown, and the version read. */
    public record AcceptRequest(String otp, String consentVersion, Long version) {
    }

    /** Borrower decline: {@code NOT_NOW} or {@code DID_NOT_ASK}. */
    public record DeclineRequest(String reason, Long version) {
    }

    public record OtpSentView(boolean sent, int ttlSeconds, String devCode) {
    }

    /** One extension as staff see it. */
    public record ExtensionView(UUID id, Long loanId, UUID caseId, int seq, String status, long version,
                                long feePaise, long gstPaise, long interestPaise, LocalDate payBy, Instant expiresAt,
                                LocalDate oldDueDate, LocalDate newDueDate, String callNote, String recordingId,
                                String overrideReason, Long proposedBy, Instant proposedAt, Long approvedBy,
                                Instant approvedAt, Long rejectedBy, String rejectReason, String rejectNote,
                                Long cancelledBy, String cancelReason, Instant acceptedAt, Instant declinedAt,
                                String declinedReason, Instant seenByBorrowerAt, Long paymentId, Instant activatedAt) {

        public static ExtensionView of(LoanExtension e) {
            return new ExtensionView(e.getId(), e.getLoanId(), e.getCollectionCaseId(), e.getSeq(), e.getStatus().name(),
                    e.getVersion(), e.getFeePaise(), e.getGstPaise(), e.getInterestPaise(), e.getPayBy(),
                    e.getExpiresAt(), e.getOldDueDate(), e.getNewDueDate(), e.getCallNote(), e.getRecordingId(),
                    e.getOverrideReason(), e.getProposedBy(), e.getProposedAt(), e.getApprovedBy(), e.getApprovedAt(),
                    e.getRejectedBy(), e.getRejectReason(), e.getRejectNote(), e.getCancelledBy(), e.getCancelReason(),
                    e.getAcceptedAt(), e.getDeclinedAt(), e.getDeclinedReason(), e.getSeenByBorrowerAt(),
                    e.getPaymentId(), e.getActivatedAt());
        }
    }

    /**
     * The case's extension panel (G2): whether an offer can be made now and why not ({@code reasonCode}), the
     * cap and how many are used, today's quote, the open extension and the history.
     */
    public record ExtensionPanel(UUID caseId, Long loanId, boolean eligible, String reasonCode, int cap, int used,
                                 ExtensionQuote quoteForToday, LocalDate dueDate, LocalDate originalDueDate,
                                 LocalDate newDueDate, ExtensionView current, List<ExtensionView> history) {
    }

    /** What the borrower sees of an extension: no staff ids or call notes. */
    public record BorrowerExtensionView(UUID id, Long loanId, int seq, String status, long version, LocalDate payBy,
                                        Instant expiresAt, LocalDate oldDueDate, LocalDate newDueDate,
                                        Instant acceptedAt, Instant declinedAt, Instant activatedAt) {

        public static BorrowerExtensionView of(LoanExtension e) {
            return new BorrowerExtensionView(e.getId(), e.getLoanId(), e.getSeq(), e.getStatus().name(), e.getVersion(),
                    e.getPayBy(), e.getExpiresAt(), e.getOldDueDate(), e.getNewDueDate(), e.getAcceptedAt(),
                    e.getDeclinedAt(), e.getActivatedAt());
        }
    }
}
