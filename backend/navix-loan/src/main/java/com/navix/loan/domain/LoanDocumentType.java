package com.navix.loan.domain;

/**
 * Types of loan documents generated/stored during the lifecycle.
 *
 * <ul>
 *   <li>{@link #AGREEMENT} - the signed loan agreement.</li>
 *   <li>{@link #SANCTION} - the sanction letter.</li>
 *   <li>{@link #KFS} - the Key Facts Statement.</li>
 *   <li>SCHEDULE, CLOSURE_RECEIPT, EXTENSION_ADDENDUM, EMI_ADDENDUM - DhanNow Phases 5-6 (V97).</li>
 * </ul>
 */
public enum LoanDocumentType {
    AGREEMENT,
    SANCTION,
    KFS,
    SCHEDULE,
    CLOSURE_RECEIPT,
    EXTENSION_ADDENDUM,
    EMI_ADDENDUM
}
