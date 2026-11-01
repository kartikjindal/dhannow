package com.navix.loan.domain;

import java.math.BigDecimal;

/**
 * One immutable version of the loan pricing ({@code pricing_config}, V92), as frozen on an offer and
 * copied to the loan. {@code LoanMath} computes every money figure from one of these.
 *
 * @param versionId    {@code pricing_config.id}; null for the navix seed defaults (loans and
 *                     applications created before V92 carry no version)
 * @param version      the human version number ({@code pricing_config.version})
 * @param tenureDays   fixed tenure for {@link InterestMode#UPFRONT}; the fallback term for ACCRUED
 * @param penalCapDays the late charge stops after this many days
 * @param prepayRebate whether early repayment refunds unused interest (DhanNow: false)
 */
public record PricingSnapshot(Long versionId, int version, BigDecimal pfRate, BigDecimal gstRate,
                              BigDecimal dailyRate, int tenureDays, InterestMode interestMode,
                              boolean penalEnabled, BigDecimal penalRate, int penalCapDays,
                              boolean prepayRebate) {

    public boolean upfront() {
        return interestMode == InterestMode.UPFRONT;
    }
}
