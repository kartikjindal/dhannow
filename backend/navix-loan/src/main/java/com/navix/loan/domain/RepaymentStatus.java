package com.navix.loan.domain;

/**
 * Where the borrower's repayment stands ({@code loan.repayment_status}, PLAN Phase 3), kept by
 * {@code RepaymentService}: a payment awaiting the accountant wins over PARTIAL; PAID once closed.
 */
public enum RepaymentStatus {
    UNPAID,
    PARTIAL,
    PAID_PENDING_VERIFICATION,
    PAID
}
