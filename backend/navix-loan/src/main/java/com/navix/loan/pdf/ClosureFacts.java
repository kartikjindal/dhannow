package com.navix.loan.pdf;

import java.time.LocalDate;
import java.util.List;

/**
 * What a closure receipt states beyond {@link DocumentFacts} (PLAN Phase 6a): the verified payments and how
 * the waterfall split them ({@code payment_allocation}). Read from the ledger, never recomputed.
 *
 * @param settled principal was not paid in full: the loan closed on an approved settlement
 */
public record ClosureFacts(LocalDate closedOn, List<PaidLine> payments, long penalPaise, long principalPaise,
                           long refundPaise, boolean settled) {

    public record PaidLine(LocalDate paidOn, String reference, long amountPaise) {
    }

    public long totalPaise() {
        return payments.stream().mapToLong(PaidLine::amountPaise).sum();
    }
}
