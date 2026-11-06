package com.navix.collections.entity;

import com.navix.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A collections-led loan extension ({@code loan_extension}, V103; PLAN Phase 6b): one more tenure for the
 * fee, GST and interest of that tenure plus the late charge to the bank date. The status changes only
 * through {@link #moveTo}, which checks {@link ExtensionStatus}; {@code @Version} turns a lost race into
 * 409 STALE_STATE.
 */
@Entity
@Table(name = "loan_extension")
@Getter
@Setter
@NoArgsConstructor
public class LoanExtension {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Column(name = "collection_case_id", nullable = false)
    private UUID collectionCaseId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Setter(AccessLevel.NONE)
    private ExtensionStatus status = ExtensionStatus.OFFERED;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "fee_paise", nullable = false)
    private long feePaise;

    @Column(name = "gst_paise", nullable = false)
    private long gstPaise;

    @Column(name = "interest_paise", nullable = false)
    private long interestPaise;

    @Column(name = "pay_by", nullable = false)
    private LocalDate payBy;

    /** 23:59:59 IST on {@link #payBy}. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "old_due_date", nullable = false)
    private LocalDate oldDueDate;

    @Column(name = "new_due_date", nullable = false)
    private LocalDate newDueDate;

    /** The officer's note of the borrower call, and the call recording id (CC-71). */
    @Column(name = "call_note")
    private String callNote;

    @Column(name = "recording_id")
    private String recordingId;

    /** Set when an ADMIN offered past the cap (EXT-25). */
    @Column(name = "override_reason")
    private String overrideReason;

    @Column(name = "proposed_by", nullable = false)
    private Long proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_by")
    private Long rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "reject_note")
    private String rejectNote;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "consent_version")
    private String consentVersion;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "declined_reason")
    private String declinedReason;

    @Column(name = "seen_by_borrower_at")
    private Instant seenByBorrowerAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    /** The verified {@code payment} that activated it. */
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "resend_day")
    private LocalDate resendDay;

    @Column(name = "resend_count", nullable = false)
    private int resendCount;

    /** The one status writer: an illegal move is 409 STALE_STATE with the current state. */
    public void moveTo(ExtensionStatus next) {
        if (!status.canMoveTo(next)) {
            throw new ConflictException("STALE_STATE",
                    "This extension is " + status.name().toLowerCase() + " and cannot become "
                            + next.name().toLowerCase() + ".", status.name());
        }
        status = next;
    }
}
