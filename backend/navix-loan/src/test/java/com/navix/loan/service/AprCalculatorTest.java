package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.domain.ProductSnapshot;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** PLAN Phase 5: the APR shown on every document, pinned on the worked example. */
class AprCalculatorTest {

    /** ₹1,000 → ₹742 on day 0, ₹1,000 back on day 14: IRR compounded = (1000/742)^(365/14) − 1. */
    @Test
    void irrAprIsPinnedForTheOneThousandRupeeLoan() {
        assertThat(AprCalculator.Method.IRR.aprPercent(74_200, 100_000, 14)).isEqualByComparingTo("239096.78");
    }

    /** Every ladder amount has the same cash-flow ratio, so the same APR. */
    @Test
    void everyLadderAmountHasTheSameApr() {
        LoanMath math = new LoanMath(LoanMath.DHANNOW_SEED_PRICING);
        for (long p : ProductSnapshot.SEED.allowedAmountsPaise()) {
            assertThat(AprCalculator.Method.IRR.aprPercent(math.netDisbursedPaise(p), math.totalRepayablePaise(p, 14), 14))
                    .as("₹%d", p / 100).isEqualByComparingTo("239096.78");
        }
    }

    /** The switch Compliance may choose: r × 365/14. */
    @Test
    void simpleAnnualisationIsTheAlternative() {
        assertThat(AprCalculator.Method.SIMPLE.aprPercent(74_200, 100_000, 14)).isEqualByComparingTo("906.53");
        assertThat(AprCalculator.Method.valueOf("SIMPLE").aprPercent(74_200, 100_000, 14).scale()).isEqualTo(2);
        assertThat(AprCalculator.Method.IRR.aprPercent(100_000, 100_000, 14)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
