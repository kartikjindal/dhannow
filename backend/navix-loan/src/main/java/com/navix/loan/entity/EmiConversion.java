package com.navix.loan.entity;

import com.navix.common.exception.ConflictException;
import com.navix.loan.domain.EmiStatus;
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
 * A recovery EMI conversion ({@code emi_conversion}, V104; PLAN Phase 6c). The status changes only through
 * {@link #moveTo}; {@code @Version} turns a lost race into 409 STALE_STATE. The money columns are written
 * once, when the borrower's acceptance activates it.
 */
@Entity
@Table(name = "emi_conversion")
@Getter
@Setter
@NoArgsConstructor
public class EmiConversion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Setter(AccessLevel.NONE)
    private EmiStatus status = EmiStatus.REQUESTED;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "instalment_count", nullable = false)
    private int instalmentCount;

    @Column(name = "gap_days", nullable = false)
    private int gapDays;

    @Column(name = "accept_by", nullable = false)
    private LocalDate acceptBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    private String note;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

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

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "conversion_date")
    private LocalDate conversionDate;

    @Column(name = "principal_paise")
    private Long principalPaise;

    @Column(name = "charges_paise")
    private Long chargesPaise;

    @Column(name = "interest_mode")
    private String interestMode;

    @Column(name = "schedule_version")
    private Integer scheduleVersion;

    @Column(name = "activated_at")
    private Instant activatedAt;

    /** Move to {@code next}, or 409 STALE_STATE with the current state. */
    public void moveTo(EmiStatus next) {
        if (!status.canMoveTo(next)) {
            throw new ConflictException("STALE_STATE", "This EMI offer is " + status.name().toLowerCase() + ".",
                    status.name());
        }
        status = next;
    }
}
