package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.domain.PricingSnapshot;
import com.navix.loan.domain.ProductSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The DhanNow v1 pricing (V92 seed: UPFRONT, 10% fee, 18% GST on the fee, 14 × 1%/day interest) against
 * the handover deck's UAT table (docs/product/DhanNow_Loan_Engine_Difference.md §2.1). Must match
 * site/calc.js and the KFS figures. Invariants over every amount are in
 * {@link LoanMathDhanNowPropertiesTest}.
 */
class LoanMathDhanNowTest {

    private final LoanMath math = new LoanMath(LoanMath.DHANNOW_SEED_PRICING);

    @ParameterizedTest(name = "₹{0}: fee {1} + GST {2} + interest {3} → net {4}, repay {0}")
    @CsvSource({
            "1000,  100,  18,  140,  742",
            "2000,  200,  36,  280, 1484",
            "3000,  300,  54,  420, 2226",
            "4000,  400,  72,  560, 2968",
            "5000,  500,  90,  700, 3710",
            "7000,  700, 126,  980, 5194",
            "10000, 1000, 180, 1400, 7420"})
    void workedTable(long principal, long fee, long gst, long interest, long net) {
        long p = principal * 100;

        assertThat(math.processingFeePaise(p)).isEqualTo(fee * 100);
        assertThat(math.gstPaise(p)).isEqualTo(gst * 100);
        assertThat(math.upfrontInterestPaise(p)).isEqualTo(interest * 100);
        assertThat(math.netDisbursedPaise(p)).isEqualTo(net * 100);
        // Repayment is the principal only: the interest was deducted at disbursal.
        assertThat(math.totalRepayablePaise(p, 14)).isEqualTo(p);
        assertThat(math.outstandingPaise(p, 14, 0, 0)).isEqualTo(p);
    }

    @Test
    void earlyRepaymentIsStillThePrincipalWithNoRebate() {
        assertThat(math.accruedInterestPaise(100_000L, 3)).isZero();
        assertThat(math.totalRepayablePaise(100_000L, 3)).isEqualTo(100_000L);
        assertThat(math.pricing().prepayRebate()).isFalse();
    }

    @Test
    @DisplayName("CC-01: due 14 days from the disbursal date, at 23:59:59 IST, no holiday shift")
    void cc01_dueIsDisbursalPlusFourteenAtTheIstCutoff() {
        LocalDate due = math.dueDate(LocalDate.of(2026, 12, 18), 30); // the salary day is ignored
        assertThat(due).isEqualTo(LocalDate.of(2027, 1, 1));           // a holiday: not shifted
        assertThat(LoanMath.dueAt(due, ProductSnapshot.SEED)).isEqualTo(Instant.parse("2027-01-01T18:29:59Z"));
    }

    @Test
    void lateChargeIsTwoPercentADayOnPrincipalCappedAtThirtyDays() {
        assertThat(math.latePenaltyPaise(100_000L, 1)).isEqualTo(2_000L);
        assertThat(math.latePenaltyPaise(100_000L, 30)).isEqualTo(60_000L);
        assertThat(math.latePenaltyPaise(100_000L, 45)).isEqualTo(60_000L);
        assertThat(math.outstandingPaise(100_000L, 0, 2, 50_000L)).isEqualTo(54_000L);
        assertThat(math.penalCapDays()).isEqualTo(30);
    }

    @Test
    void aVersionWithTheLateChargeOffChargesNone() {
        PricingSnapshot s = LoanMath.DHANNOW_SEED_PRICING;
        LoanMath off = new LoanMath(new PricingSnapshot(7L, 2, s.pfRate(), s.gstRate(), s.dailyRate(),
                s.tenureDays(), s.interestMode(), false, s.penalRate(), s.penalCapDays(), false));
        assertThat(off.latePenaltyPaise(100_000L, 10)).isZero();
    }

    @Test
    void ratesComeFromTheSnapshotNotTheConstants() {
        PricingSnapshot s = LoanMath.DHANNOW_SEED_PRICING;
        LoanMath v2 = new LoanMath(new PricingSnapshot(8L, 2, new BigDecimal("0.05"), s.gstRate(),
                new BigDecimal("0.005"), 7, s.interestMode(), true, s.penalRate(), s.penalCapDays(), false));
        // ₹1,000: fee 50 + GST 9 + 7 × 0.5% interest 35 → net 906.
        assertThat(v2.netDisbursedPaise(100_000L)).isEqualTo(90_600L);
        assertThat(v2.dueDate(LocalDate.of(2026, 11, 1), null)).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    void theNavixDefaultsAreUnchanged() {
        LoanMath navix = new LoanMath();
        assertThat(navix.pricing()).isEqualTo(LoanMath.NAVIX_PRICING);
        assertThat(navix.upfrontInterestPaise(1_000_000L)).isZero();
        assertThat(navix.netDisbursedPaise(1_000_000L)).isEqualTo(882_000L);
        assertThat(navix.totalRepayablePaise(1_000_000L, 30)).isEqualTo(1_300_000L);
        assertThat(navix.dueDate(LocalDate.of(2026, 6, 3), null)).isEqualTo(LocalDate.of(2026, 7, 1));
    }
}
