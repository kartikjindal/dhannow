package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.service.PaymentAllocator.Component;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The waterfall (PLAN Phase 6a): penal → principal, the rest is excess. */
class PaymentAllocatorTest {

    private static final List<Component> ORDER = List.of(Component.PENAL, Component.INTEREST, Component.PRINCIPAL);

    /** CC-09: a part payment on a late loan clears the late charge first, then principal. */
    @Test
    void cc09_partPaymentPaysTheLateChargeBeforePrincipal() {
        var split = PaymentAllocator.split(50_000L, ORDER,
                Map.of(Component.PENAL, 4_000L, Component.INTEREST, 0L, Component.PRINCIPAL, 100_000L));
        assertThat(split).containsExactly(Map.entry(Component.PENAL, 4_000L), Map.entry(Component.PRINCIPAL, 46_000L));
    }

    /** CC-08: anything beyond what is owed is excess, for a refund case. */
    @Test
    void cc08_overpaymentLeavesAnExcess() {
        var split = PaymentAllocator.split(110_000L, ORDER, Map.of(Component.PENAL, 0L, Component.PRINCIPAL, 100_000L));
        assertThat(split).containsExactly(Map.entry(Component.PRINCIPAL, 100_000L), Map.entry(Component.EXCESS, 10_000L));
    }

    @Test
    void aClosedLoanTakesEverythingAsExcess() {
        assertThat(PaymentAllocator.split(30_000L, ORDER, Map.of())).containsExactly(Map.entry(Component.EXCESS, 30_000L));
    }

    @Test
    void theConfiguredOrderDecides() {
        var split = PaymentAllocator.split(5_000L, List.of(Component.PRINCIPAL, Component.PENAL),
                Map.of(Component.PENAL, 4_000L, Component.PRINCIPAL, 100_000L));
        assertThat(split).containsExactly(Map.entry(Component.PRINCIPAL, 5_000L));
    }
}
