package com.navix.loan.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * DhanNow loan lifecycle (PLAN Phase 3), persisted as {@code loan.lifecycle_status}. Each state
 * declares where it may go; {@link #canTransitionTo} is enforced by {@code Loan.moveTo}, the one
 * writer, which also keeps the navix {@code status} column in step ({@link #legacyStatus}).
 *
 * <pre>
 *  DISBURSEMENT_PENDING → ACTIVE → DUE_TODAY → OVERDUE_D1 → OVERDUE_REVIEW
 *  OVERDUE_* → RESTRUCTURE_PENDING → RESTRUCTURED (Phase 6)
 *  DUE_TODAY / OVERDUE_* → ACTIVE when an extension activates (Phase 6b)
 *  any disbursed state → CLOSED (outstanding 0 after verified reconciliation)
 * </pre>
 *
 * <p>The timed part (ACTIVE … OVERDUE_REVIEW) is declared in order, so the timeline is the ordinal.
 */
public enum LoanLifecycle {
    DISBURSEMENT_PENDING(LoanStatus.DISBURSING, false),
    ACTIVE(LoanStatus.ACTIVE, true),
    DUE_TODAY(LoanStatus.ACTIVE, true),
    // navix stored ACTIVE on a past-due loan and computed OVERDUE on read; IN_COLLECTIONS ↔ REVIEW.
    OVERDUE_D1(LoanStatus.ACTIVE, true),
    OVERDUE_REVIEW(LoanStatus.IN_COLLECTIONS, true),
    RESTRUCTURE_PENDING(LoanStatus.IN_COLLECTIONS, false),
    RESTRUCTURED(LoanStatus.ACTIVE, false),
    CLOSED(LoanStatus.CLOSED, false);

    private static final Map<LoanLifecycle, Set<LoanLifecycle>> TRANSITIONS = new EnumMap<>(LoanLifecycle.class);

    static {
        // Phase 4: the accountant's confirmation of the disbursal starts day 0.
        TRANSITIONS.put(DISBURSEMENT_PENDING, EnumSet.of(ACTIVE));
        TRANSITIONS.put(ACTIVE, EnumSet.of(DUE_TODAY, CLOSED));
        // Phase 6b: an activated extension moves the due date and puts the loan back to ACTIVE.
        TRANSITIONS.put(DUE_TODAY, EnumSet.of(OVERDUE_D1, ACTIVE, CLOSED));
        TRANSITIONS.put(OVERDUE_D1, EnumSet.of(OVERDUE_REVIEW, RESTRUCTURE_PENDING, ACTIVE, CLOSED));
        TRANSITIONS.put(OVERDUE_REVIEW, EnumSet.of(RESTRUCTURE_PENDING, ACTIVE, CLOSED));
        TRANSITIONS.put(RESTRUCTURE_PENDING, EnumSet.of(RESTRUCTURED, CLOSED));
        TRANSITIONS.put(RESTRUCTURED, EnumSet.of(CLOSED));
        TRANSITIONS.put(CLOSED, EnumSet.noneOf(LoanLifecycle.class));
    }

    private final LoanStatus legacyStatus;
    private final boolean timed;

    LoanLifecycle(LoanStatus legacyStatus, boolean timed) {
        this.legacyStatus = legacyStatus;
        this.timed = timed;
    }

    public boolean canTransitionTo(LoanLifecycle next) {
        return TRANSITIONS.get(this).contains(next);
    }

    /** The navix {@code status} value written alongside (kept for navix reports until it is dropped). */
    public LoanStatus legacyStatus() {
        return legacyStatus;
    }

    /** True when {@code target} is further along the timed path than this state. */
    public boolean isBefore(LoanLifecycle target) {
        return timed && target.timed && ordinal() < target.ordinal();
    }

    /** The next timed state; only valid while {@link #isBefore} some later state. */
    public LoanLifecycle nextOnTimeline() {
        return values()[ordinal() + 1];
    }

    /**
     * Where the clock alone puts a loan due at {@code dueAt}: ACTIVE before the due date, DUE_TODAY
     * from 00:00 on it through {@code dueAt} (23:59:59), OVERDUE_D1 after, OVERDUE_REVIEW once days
     * past due ≥ {@code reviewDpd}. Derived from the time, never from the last run (CC-13).
     */
    public static LoanLifecycle forTime(Instant now, Instant dueAt, ZoneId zone, int reviewDpd) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate dueDate = LocalDate.ofInstant(dueAt, zone);
        if (!now.isAfter(dueAt)) {
            return today.isBefore(dueDate) ? ACTIVE : DUE_TODAY;
        }
        return ChronoUnit.DAYS.between(dueDate, today) >= reviewDpd ? OVERDUE_REVIEW : OVERDUE_D1;
    }
}
