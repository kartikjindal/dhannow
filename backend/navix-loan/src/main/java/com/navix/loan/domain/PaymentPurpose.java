package com.navix.loan.domain;

/**
 * What a payment pays for ({@code payment.purpose}, V103). Only REPAYMENT money reduces what the borrower
 * owes; an EXTENSION payment buys 14 more days and never touches principal (PLAN Phase 6b).
 */
public enum PaymentPurpose {
    REPAYMENT,
    EXTENSION
}
