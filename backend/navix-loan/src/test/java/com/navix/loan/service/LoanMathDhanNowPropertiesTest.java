package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.domain.ProductSnapshot;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/** jqwik invariants of the DhanNow v1 money math over every ladder amount (PLAN Phase 1). */
class LoanMathDhanNowPropertiesTest {

    private final LoanMath math = new LoanMath(LoanMath.DHANNOW_SEED_PRICING);

    @Provide
    Arbitrary<Long> ladderAmounts() {
        return Arbitraries.of(ProductSnapshot.SEED.allowedAmountsPaise());
    }

    @Property
    @Label("CC-20: fee + GST + interest + net == principal, all whole paise, for every ladder amount")
    void cc20_deductionsAndNetSumToThePrincipal(@ForAll("ladderAmounts") long principal) {
        long fee = math.processingFeePaise(principal);
        long gst = math.gstPaise(principal);
        long interest = math.upfrontInterestPaise(principal);
        long net = math.netDisbursedPaise(principal);

        assertThat(fee + gst + interest + net).isEqualTo(principal);
        // Ladder amounts give exact paise: nothing was rounded.
        assertThat(fee * 10).isEqualTo(principal);
        assertThat(gst * 100).isEqualTo(fee * 18);
        assertThat(interest * 100).isEqualTo(principal * 14);
        assertThat(net).isPositive();
    }

    @Property
    void theRepaymentIsThePrincipalWhateverTheDaysHeld(@ForAll("ladderAmounts") long principal,
                                                       @ForAll @IntRange(min = 0, max = 60) int days) {
        assertThat(math.totalRepayablePaise(principal, days)).isEqualTo(principal);
    }

    @Property
    @Label("CC-19: the late charge stops growing after 30 days")
    void cc19_lateChargeIsCapped(@ForAll("ladderAmounts") long principal,
                                 @ForAll @IntRange(min = 0, max = 400) int daysLate) {
        long charge = math.latePenaltyPaise(principal, daysLate);
        assertThat(charge).isEqualTo(math.latePenaltyPaise(principal, Math.min(daysLate, 30)));
        assertThat(charge).isLessThanOrEqualTo(principal * 2 * 30 / 100);
    }

    @Property
    void anyPrincipalStillSumsExactly(@ForAll @net.jqwik.api.constraints.LongRange(min = 1, max = 100_000_000) long principal) {
        long sum = math.processingFeePaise(principal) + math.gstPaise(principal)
                + math.upfrontInterestPaise(principal) + math.netDisbursedPaise(principal);
        assertThat(sum).isEqualTo(principal);
    }
}
