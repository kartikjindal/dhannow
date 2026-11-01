package com.navix.loan.service;

import static org.mockito.Mockito.mock;

import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.domain.PricingSnapshot;
import com.navix.loan.domain.ProductSnapshot;
import com.navix.loan.entity.PricingConfig;
import com.navix.loan.entity.ProductConfig;
import com.navix.loan.repository.PricingConfigRepository;
import com.navix.loan.repository.ProductConfigRepository;
import java.time.Instant;

/** Test wiring for {@link PricingService}. */
final class TestPricing {

    private TestPricing() {
    }

    /**
     * A PricingService with no config rows behind it: applications and loans without a frozen version
     * (every navix test fixture) price on {@link LoanMath#NAVIX_PRICING}, exactly as before V92.
     */
    static PricingService navix() {
        return new PricingService(mock(PricingConfigRepository.class), mock(ProductConfigRepository.class));
    }

    /** A {@code pricing_config} row with {@code terms}. */
    static PricingConfig pricingRow(long id, int version, PricingSnapshot terms, ConfigStatus status) {
        PricingConfig row = new PricingConfig();
        row.setId(id);
        row.setVersion(version);
        row.setPfRate(terms.pfRate());
        row.setGstRate(terms.gstRate());
        row.setDailyRate(terms.dailyRate());
        row.setTenureDays(terms.tenureDays());
        row.setInterestMode(terms.interestMode());
        row.setPenalEnabled(terms.penalEnabled());
        row.setPenalRate(terms.penalRate());
        row.setPenalCapDays(terms.penalCapDays());
        row.setPrepayRebate(terms.prepayRebate());
        row.setEffectiveFrom(Instant.parse("2026-11-01T00:00:00Z"));
        row.setStatus(status);
        return row;
    }

    /** The V93 seed {@code product_config} row (the ₹1k–10k ladder). */
    static ProductConfig seedProductRow(long id, ConfigStatus status) {
        ProductSnapshot seed = ProductSnapshot.SEED;
        ProductConfig row = new ProductConfig();
        row.setId(id);
        row.setVersion(1);
        row.setAllowedAmountsPaise(seed.allowedAmountsPaise().toArray(Long[]::new));
        row.setMinPaise(seed.minPaise());
        row.setMaxPaise(seed.maxPaise());
        row.setDueCutoffTime(seed.dueCutoff());
        row.setTimezone(seed.zone().getId());
        row.setOneLiveLoan(true);
        row.setLateBlocksUpgradeDays(1);
        row.setOverdueReviewDpd(seed.overdueReviewDpd());
        row.setStatus(status);
        return row;
    }
}
