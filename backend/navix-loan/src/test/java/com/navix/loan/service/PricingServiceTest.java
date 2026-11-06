package com.navix.loan.service;

import static com.navix.loan.service.TestPricing.pricingRow;
import static com.navix.loan.service.TestPricing.seedProductRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.domain.InterestMode;
import com.navix.loan.domain.PricingSnapshot;
import com.navix.loan.domain.ProductSnapshot;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.PricingConfig;
import com.navix.loan.repository.PricingConfigRepository;
import com.navix.loan.repository.ProductConfigRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    private PricingConfigRepository pricingRepo;
    @Mock
    private ProductConfigRepository productRepo;

    private PricingService pricing;
    private PricingConfig v1;

    @BeforeEach
    void setUp() {
        pricing = new PricingService(pricingRepo, productRepo);
        v1 = pricingRow(1L, 1, LoanMath.DHANNOW_SEED_PRICING, ConfigStatus.ACTIVE);
    }

    @Test
    void freezeStampsTheActiveVersionsOnTheOffer() {
        when(pricingRepo.findFirstByStatus(ConfigStatus.ACTIVE)).thenReturn(Optional.of(v1));
        when(productRepo.findFirstByStatus(ConfigStatus.ACTIVE)).thenReturn(Optional.of(seedProductRow(1L, ConfigStatus.ACTIVE)));
        LoanApplication app = new LoanApplication();

        pricing.freeze(app);

        assertThat(app.getPricingVersionId()).isEqualTo(1L);
        assertThat(app.getProductVersionId()).isEqualTo(1L);
    }

    @Test
    void withNoActiveVersionNothingCanBeOffered() {
        assertThatThrownBy(() -> pricing.freeze(new LoanApplication()))
                .hasFieldOrPropertyWithValue("code", "PRICING_NOT_CONFIGURED");
        assertThatThrownBy(() -> pricing.activeProduct())
                .hasFieldOrPropertyWithValue("code", "PRICING_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("CC-03: activating new pricing changes neither an open offer nor a disbursed loan")
    void cc03_frozenSnapshotOutlivesANewActiveVersion() {
        when(pricingRepo.findFirstByStatus(ConfigStatus.ACTIVE)).thenReturn(Optional.of(v1));
        when(productRepo.findFirstByStatus(ConfigStatus.ACTIVE)).thenReturn(Optional.of(seedProductRow(1L, ConfigStatus.ACTIVE)));
        when(pricingRepo.findById(1L)).thenReturn(Optional.of(v1));
        LoanApplication offer = new LoanApplication();
        pricing.freeze(offer);
        Loan loan = new Loan();
        loan.setPricingVersionId(offer.getPricingVersionId());

        // v2 (a 12% fee) is activated and v1 retired while the offer is open.
        PricingSnapshot s = LoanMath.DHANNOW_SEED_PRICING;
        PricingConfig v2 = pricingRow(2L, 2, new PricingSnapshot(null, 2, new BigDecimal("0.12"), s.gstRate(),
                s.dailyRate(), s.tenureDays(), InterestMode.UPFRONT, true, s.penalRate(), s.penalCapDays(), false),
                ConfigStatus.ACTIVE);
        v1.setStatus(ConfigStatus.RETIRED);
        when(pricingRepo.findFirstByStatus(ConfigStatus.ACTIVE)).thenReturn(Optional.of(v2));

        assertThat(pricing.mathFor(offer).netDisbursedPaise(100_000L)).isEqualTo(74_200L);
        assertThat(pricing.mathFor(loan).netDisbursedPaise(100_000L)).isEqualTo(74_200L);
        assertThat(pricing.activePricing().pfRate()).isEqualByComparingTo("0.12");
        // A new offer gets v2.
        LoanApplication next = new LoanApplication();
        pricing.freeze(next);
        assertThat(next.getPricingVersionId()).isEqualTo(2L);
        // Versions are immutable, so a snapshot is read once.
        verify(pricingRepo, times(1)).findById(1L);
    }

    @Test
    void rowsWithoutAVersionKeepTheNavixTerms() {
        assertThat(pricing.mathFor(new LoanApplication()).pricing()).isEqualTo(LoanMath.NAVIX_PRICING);
        assertThat(pricing.mathFor(new Loan()).pricing()).isEqualTo(LoanMath.NAVIX_PRICING);
        assertThat(pricing.product(null)).isEqualTo(ProductSnapshot.SEED);
    }

    @Test
    void aMissingVersionIsAnError() {
        assertThatThrownBy(() -> pricing.pricing(9L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pricing.product(9L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyTheFrozenLadderAmountsCanBeDrawn() {
        when(productRepo.findById(1L)).thenReturn(Optional.of(seedProductRow(1L, ConfigStatus.ACTIVE)));
        LoanApplication app = new LoanApplication();
        app.setProductVersionId(1L);

        pricing.requireAllowedAmount(app, 300_000L);
        assertThatThrownBy(() -> pricing.requireAllowedAmount(app, 250_000L))
                .hasFieldOrPropertyWithValue("code", "AMOUNT_NOT_ALLOWED");
        // A pre-V92 offer has no ladder.
        pricing.requireAllowedAmount(new LoanApplication(), 250_000L);
    }
}
