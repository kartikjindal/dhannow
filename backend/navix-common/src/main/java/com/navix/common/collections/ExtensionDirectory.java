package com.navix.common.collections;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port from {@code navix-loan} to the loan extensions that {@code navix-collections} owns (PLAN Phase 6b).
 * The loan module records and verifies the borrower's extension payment and moves the due date; the
 * extension's state lives in collections. Implemented by {@code ExtensionDirectoryAdapter}, which must
 * stay free of {@code LoanDirectory} (the two would otherwise form a bean cycle).
 *
 * <p>Callers hold the loan advisory lock before any write here.
 */
public interface ExtensionDirectory {

    /** One extension as the loan module needs it. {@code status} is the {@code loan_extension.status} name. */
    record ExtensionRef(UUID id, Long loanId, int seq, String status, long version, long feePaise, long gstPaise,
                        long interestPaise, LocalDate payBy, Instant expiresAt, LocalDate oldDueDate,
                        LocalDate newDueDate, Long proposedBy, Long approvedBy, Instant approvedAt,
                        Instant acceptedAt, Instant declinedAt, String declinedReason, Instant seenByBorrowerAt,
                        Long paymentId, Instant activatedAt) {

        /** Still open to the borrower: approved by the Head, not yet active or ended. */
        public boolean borrowerPending() {
            return "APPROVED".equals(status) || "ACCEPTED".equals(status) || "PAYMENT_PENDING".equals(status);
        }
    }

    Optional<ExtensionRef> find(UUID extensionId);

    /** Every extension the borrower was shown (Head-approved), oldest first. */
    List<ExtensionRef> borrowerHistory(Long loanId);

    /** An extension is open on the loan (offered and not yet active or ended); a recovery EMI waits for it. */
    default boolean hasOpen(Long loanId) {
        return false;
    }

    /** ACCEPTED → PAYMENT_PENDING once a payment for it is on file. */
    void paymentPending(UUID extensionId);

    /** PAYMENT_PENDING → ACCEPTED when that payment is rejected (EXT-19). */
    void paymentRejected(UUID extensionId);

    /** PAYMENT_PENDING → ACTIVE: the Accountant verified {@code paymentId}. */
    void activated(UUID extensionId, Long paymentId, Instant at);

    /** Cancel any open extension on a loan that closed (EXT-06, CC-69). */
    void cancelOpen(Long loanId, String reason, Instant at);

    /**
     * EXT-27: a dispute cancels an extension still on offer (OFFERED, APPROVED or ACCEPTED). One with a payment on
     * file (PAYMENT_PENDING) is left for the Accountant, and an ACTIVE one is never touched.
     *
     * @return true when an offer was cancelled
     */
    boolean cancelOffer(Long loanId, String reason, Instant at);
}
