package com.navix.common.extension;

import java.time.LocalDate;

/**
 * What a loan extension costs if paid on {@code paidOn} (PLAN Phase 6b), integer paise from the loan's
 * frozen pricing: fee + GST on the fee + interest for one more tenure, plus the late charge accrued to
 * {@code paidOn} that is still unpaid. ₹1,000 paid 3 days late: 100 + 18 + 140 + 60 = ₹318.
 */
public record ExtensionQuote(long feePaise, long gstPaise, long interestPaise, long lateChargePaise,
                             int lateDays, LocalDate paidOn) {

    @com.fasterxml.jackson.annotation.JsonProperty("totalPaise")
    public long totalPaise() {
        return feePaise + gstPaise + interestPaise + lateChargePaise;
    }
}
