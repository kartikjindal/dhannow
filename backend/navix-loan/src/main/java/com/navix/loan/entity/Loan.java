package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.common.exception.BusinessException;
import com.navix.loan.domain.LoanHold;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.RepaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A disbursed (or in-flight) loan.
 *
 * <p>Single-repayment product: the borrower repays {@code totalRepayable}
 * on the due date (aligned to their salary credit). Money math is computed
 * by {@code LoanMath}.
 *
 * TODO: extend audit fields via {@link BaseAuditEntity} (createdBy/at, etc.)
 * and add maker-checker / disbursement linkage.
 */
@Entity
@Table(name = "loan")
@Getter
@Setter
@NoArgsConstructor
public class Loan extends BaseAuditEntity {

    /** Customer / borrower reference (FK to IAM user); the loan originates
     *  from that customer's approved {@code LoanApplication}. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Sanctioned principal, in paise. */
    @Column(name = "principal", nullable = false)
    private Long principal;

    /** Up-front processing fee (10% of principal), in paise. */
    @Column(name = "processing_fee")
    private Long processingFee;

    /** GST charged on the processing fee (18% of the fee), in paise. */
    @Column(name = "gst")
    private Long gst;

    /** Net amount actually credited to the borrower, in paise. */
    @Column(name = "net_disbursed")
    private Long netDisbursed;

    /** Daily interest rate (1%/day) — a rate, not an amount. */
    @Column(name = "daily_interest_rate", precision = 6, scale = 4)
    private BigDecimal dailyInterestRate;

    /** Date the loan was disbursed. */
    @Column(name = "disbursed_on")
    private LocalDate disbursedOn;

    /** Single-repayment due date (aligned to salary credit day). */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /**
     * Penalty-free days after the due date (interest still accrues over them). Frozen per loan at
     * disbursal from {@code LoanMath.SALARY_GRACE_DAYS}; loans disbursed before V91 carry 1 (V90/V91). The Java default
     * is 1 too, so a {@code new Loan()} in a test prices like a legacy loan — only
     * {@code LoanService.disburse} stamps the current policy.
     */
    @Column(name = "grace_days", nullable = false)
    private int graceDays = 1;

    /** Total amount repayable on the due date (principal + interest), in paise. */
    @Column(name = "total_repayable")
    private Long totalRepayable;

    /** Current outstanding amount (scheduled-to-due less verified payments), in paise. */
    @Column(name = "outstanding")
    private Long outstanding;

    /** Current loan status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LoanStatus status;

    /** Bank/UPI transaction reference for the outgoing disbursal (captured at release). */
    @Column(name = "disbursal_txn_ref", length = 64)
    private String disbursalTxnRef;

    /**
     * The day the loan actually reached CLOSED/REPAID (the paid-on date of the closing verified
     * payment, or {@code dueDate} when no verified payment is on record). Null while the loan is
     * still open. {@link com.navix.loan.service.RepaymentService#outstandingBreakdownAsOf} clamps its
     * working date to this so a long-closed loan doesn't keep accruing phantom late penalty against
     * {@code LocalDate.now()}.
     */
    @Column(name = "closed_on")
    private LocalDate closedOn;

    /** The pricing/product versions copied from the application at disbursal (V94); null on navix loans. */
    @Column(name = "pricing_version_id")
    private Long pricingVersionId;

    @Column(name = "product_version_id")
    private Long productVersionId;

    /** Interest deducted at disbursal (UPFRONT pricing), in paise; null on navix loans. */
    @Column(name = "upfront_interest_paise")
    private Long upfrontInterestPaise;

    @Column(name = "tenure_days")
    private Integer tenureDays;

    /** The instant the loan falls due: {@code dueDate} at 23:59:59 IST (product cutoff). */
    @Column(name = "due_at")
    private Instant dueAt;

    /**
     * DhanNow lifecycle (V97). Written only through {@link #moveTo} (and {@link #startPending} on a new
     * row), which keep {@link #status} in step. {@code LoanService.disburse} mints the row
     * DISBURSEMENT_PENDING (PLAN Phase 4); a bare {@code new Loan()} defaults to ACTIVE.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 32)
    @Setter(lombok.AccessLevel.NONE)
    private LoanLifecycle lifecycleStatus = LoanLifecycle.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "hold_status", nullable = false, length = 16)
    private LoanHold holdStatus = LoanHold.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "repayment_status", nullable = false, length = 32)
    private RepaymentStatus repaymentStatus = RepaymentStatus.UNPAID;

    /**
     * Move the lifecycle to {@code next} and write the matching navix {@code status}. The same state
     * is a no-op (false); a move the map forbids is {@code ILLEGAL_TRANSITION}.
     */
    public boolean moveTo(LoanLifecycle next) {
        if (lifecycleStatus == next) {
            return false;
        }
        if (!lifecycleStatus.canTransitionTo(next)) {
            throw new BusinessException("ILLEGAL_TRANSITION", lifecycleStatus + " → " + next + " is not allowed");
        }
        lifecycleStatus = next;
        status = next.legacyStatus();
        return true;
    }

    /** A new loan waits for the Accountant to confirm the transfer (PLAN Phase 4). */
    public void startPending() {
        lifecycleStatus = LoanLifecycle.DISBURSEMENT_PENDING;
        status = LoanLifecycle.DISBURSEMENT_PENDING.legacyStatus();
    }

    /**
     * Step along the timed path up to {@code target}, one legal move at a time, so a loan the
     * scheduler missed for days still passes through every state (CC-13). False when already there
     * or past it.
     */
    public boolean advanceTo(LoanLifecycle target) {
        boolean moved = false;
        while (lifecycleStatus.isBefore(target)) {
            moved |= moveTo(lifecycleStatus.nextOnTimeline());
        }
        return moved;
    }

    /**
     * CC-11: no NACH presentment while a manual payment awaits verification, the loan is disputed,
     * or a restructure is pending.
     */
    public boolean presentmentPaused() {
        return holdStatus == LoanHold.DISPUTED
                || repaymentStatus == RepaymentStatus.PAID_PENDING_VERIFICATION
                || lifecycleStatus == LoanLifecycle.RESTRUCTURE_PENDING;
    }

    /**
     * Effective status on read: an ACTIVE loan past its due date reads as OVERDUE. This is computed,
     * not persisted (the stored column stays ACTIVE until a collection case flips it to
     * IN_COLLECTIONS) — collectible queries already include both ACTIVE and OVERDUE.
     */
    public LoanStatus effectiveStatus(LocalDate asOf) {
        if (status == LoanStatus.ACTIVE && dueDate != null && asOf != null && asOf.isAfter(dueDate)) {
            return LoanStatus.OVERDUE;
        }
        return status;
    }
}
