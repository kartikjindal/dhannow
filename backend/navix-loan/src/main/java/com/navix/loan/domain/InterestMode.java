package com.navix.loan.domain;

/**
 * When a pricing version charges interest ({@code pricing_config.interest_mode}, V92).
 *
 * <p>{@link #ACCRUED} is navix: 1%/day accrues and is collected at repayment. {@link #UPFRONT} is
 * DhanNow: the whole tenure's interest is deducted from the disbursal with the fee and GST, and the
 * borrower repays the principal only.
 */
public enum InterestMode {
    UPFRONT,
    ACCRUED
}
