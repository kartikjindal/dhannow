package com.navix.loan.domain;

/**
 * A hold on a loan ({@code loan.hold_status}, PLAN Phase 3), independent of its lifecycle.
 * DISPUTED pauses reminders, collections calls and NACH presentment; FRAUD_REVIEW blocks new
 * applications for the customer. Set by the dispute flow (Phase 7) and staff.
 */
public enum LoanHold {
    NONE,
    DISPUTED,
    FRAUD_REVIEW
}
