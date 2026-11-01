package com.navix.loan.domain;

/**
 * Verification state of a recorded repayment. A "paid" claim requires proof
 * (transaction id and/or screenshot) before it can move to VERIFIED.
 */
public enum PaymentStatus {
    PENDING_VERIFICATION,
    VERIFIED,
    REJECTED,
    /** Verified, then found bounced or reversed by the bank (CC-10, V104). Never counts. */
    REVERSED
}
