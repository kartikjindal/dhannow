package com.navix.loan.domain;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * One immutable version of the product rules ({@code product_config}, V93): the ladder amounts and
 * when a due day ends. Frozen on an offer next to its {@link PricingSnapshot}.
 *
 * @param versionId          {@code product_config.id}; null for the seed defaults
 * @param allowedAmountsPaise the selectable amounts, ascending; level n of the credit ladder is the
 *                            n-th entry
 * @param dueCutoff          the time of day a due date ends (23:59:59)
 * @param zone               the zone that time is read in (Asia/Kolkata)
 * @param overdueReviewDpd   days past due at which OVERDUE_D1 becomes OVERDUE_REVIEW (7)
 * @param offerValidityDays  days an offer stays open before it must be re-quoted (3, CC-02)
 * @param extensionMax       loan extensions allowed per loan without an ADMIN override (3, V103)
 * @param emiGapDays         days between recovery-EMI instalments, from the conversion date (10, V104)
 * @param emiThreeFromPaise  principal from which a recovery EMI has three instalments, not two (₹7,000, V104)
 */
public record ProductSnapshot(Long versionId, List<Long> allowedAmountsPaise, long minPaise,
                              long maxPaise, LocalTime dueCutoff, ZoneId zone, int overdueReviewDpd,
                              int offerValidityDays, int extensionMax, int emiGapDays, long emiThreeFromPaise) {

    public ProductSnapshot {
        allowedAmountsPaise = List.copyOf(allowedAmountsPaise);
    }

    /** The seed ladder (₹1k → 2k → 3k → 4k → 5k → 7k → 10k), identical to the V93 seed row. */
    public static final ProductSnapshot SEED = new ProductSnapshot(null,
            List.of(100_000L, 200_000L, 300_000L, 400_000L, 500_000L, 700_000L, 1_000_000L),
            100_000L, 1_000_000L, LocalTime.of(23, 59, 59), ZoneId.of("Asia/Kolkata"), 7, 3, 3, 10, 700_000L);

    /** Recovery EMI instalments for a loan of {@code principalPaise}: two, or three from the configured amount. */
    public int emiInstalments(long principalPaise) {
        return principalPaise >= emiThreeFromPaise ? 3 : 2;
    }
}
