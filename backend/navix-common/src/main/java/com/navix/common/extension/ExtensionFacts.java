package com.navix.common.extension;

import java.time.LocalDate;

/**
 * Where a loan stands for an extension offer, from the loan module (PLAN Phase 6b). Collections adds its
 * own checks (open settlement, open extension) on top.
 *
 * @param ineligibleReason null when the loan itself allows an offer; else a reason code
 *                         ({@code NOT_DUE_YET}, {@code LOAN_CLOSED}, {@code ON_HOLD}, {@code REPAYMENT_PENDING},
 *                         {@code DEBIT_IN_FLIGHT}, {@code EXTENSION_CAP_REACHED})
 * @param newDueDate       the due date an extension would give: the current due date + one tenure
 */
public record ExtensionFacts(Long loanId, Long customerId, String lifecycleStatus, String holdStatus,
                             LocalDate dueDate, LocalDate originalDueDate, LocalDate newDueDate,
                             int extensionCount, int cap, String ineligibleReason, ExtensionQuote quote) {
}
