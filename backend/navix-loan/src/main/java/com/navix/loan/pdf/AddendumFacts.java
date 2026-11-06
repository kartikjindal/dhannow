package com.navix.loan.pdf;

import java.time.LocalDate;

/**
 * What an extension addendum states beyond {@link DocumentFacts} (PLAN Phase 6b): which extension, the due
 * date before and after, and how the charge was made up ({@code payment_allocation} of the extension payment).
 */
public record AddendumFacts(int seq, int cap, LocalDate oldDueDate, LocalDate newDueDate, LocalDate paidOn,
                            long feePaise, long gstPaise, long interestPaise, long lateChargePaise) {

    public long totalPaise() {
        return feePaise + gstPaise + interestPaise + lateChargePaise;
    }
}
