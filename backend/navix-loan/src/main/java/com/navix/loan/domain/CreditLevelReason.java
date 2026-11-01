package com.navix.loan.domain;

/** Why a customer's credit level was set ({@code credit_level_history.reason}, V96). */
public enum CreditLevelReason {
    /** First disbursal: the customer starts at level 1. */
    FIRST_LOAN,
    /** Closed by full repayment on or before {@code due_at}: up one level (capped at the top). */
    ON_TIME_REPAID,
    /** Closed late, or by settlement: the level stays. */
    LATE_REPAID_NO_CHANGE,
    /** Set by an ADMIN with a reason code. */
    MANUAL_EXCEPTION
}
