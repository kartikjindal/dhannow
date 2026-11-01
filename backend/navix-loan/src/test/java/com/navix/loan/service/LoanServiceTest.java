package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PricingConfigRepository;
import com.navix.loan.repository.ProductConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanServiceTest {

    @Mock
    private LoanRepository loanRepository;

    private LoanService loanService;

    @BeforeEach
    void setUp() {
        loanService = new LoanService(loanRepository, TestPricing.navix(), java.time.Clock.systemDefaultZone());
    }

    @Test
    void disburseComputesEconomicsWithSalaryLinkedDueDate() {
        LoanApplication app = new LoanApplication();
        app.setCustomerId(7L);
        app.setAmountRequested(1_000_000L); // ₹10,000
        app.setSalaryCreditDay(30);          // salary on the 30th
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Disbursed 3 Jun → latest salary credit ≤ 40d is 30 Jun (27-day tenure).
        Loan loan = loanService.disburse(app, LocalDate.of(2026, 6, 3), "UTR12345");

        assertThat(loan.getDisbursalTxnRef()).isEqualTo("UTR12345");
        assertThat(loan.getPrincipal()).isEqualTo(1_000_000L);
        assertThat(loan.getProcessingFee()).isEqualTo(100_000L);
        assertThat(loan.getGst()).isEqualTo(18_000L);
        assertThat(loan.getNetDisbursed()).isEqualTo(882_000L);
        assertThat(loan.getDueDate()).isEqualTo(LocalDate.of(2026, 6, 30)); // salary-linked ≤ 40d
        assertThat(loan.getTotalRepayable()).isEqualTo(1_270_000L); // principal + 27d interest
        assertThat(loan.getOutstanding()).isEqualTo(1_270_000L);
        // PLAN Phase 4: minted on the UTR, waiting for the Accountant.
        assertThat(loan.getLifecycleStatus()).isEqualTo(LoanLifecycle.DISBURSEMENT_PENDING);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISBURSING);
    }

    @Test
    void disbursementRecomputesDueDateFromTheSanctionedSalaryDay() {
        LoanApplication app = new LoanApplication();
        app.setCustomerId(7L);
        app.setAmountRequested(1_000_000L);
        app.setSalaryCreditDay(31);
        app.setApprovedRepaymentDate(LocalDate.of(2026, 2, 28)); // sanction-time projection
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Loan loan = loanService.disburse(app, LocalDate.of(2028, 1, 25), "UTR-LEAP");

        assertThat(loan.getDueDate()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(loan.getTotalRepayable()).isEqualTo(1_350_000L);
    }

    /**
     * DhanNow: the loan is priced on the version frozen on the offer, day 0 is the disbursal date on
     * the app clock (IST), and the due date is day 14 at 23:59:59 IST whatever the sanction projected.
     */
    @Test
    @DisplayName("CC-01: day 0 is the confirmed disbursal date; due = day 14, 23:59:59 IST")
    void cc01_dhanNowLoanStartsOnTheDisbursalDayOnItsFrozenTerms() {
        PricingConfigRepository pricingRepo = mock(PricingConfigRepository.class);
        ProductConfigRepository productRepo = mock(ProductConfigRepository.class);
        when(pricingRepo.findById(1L)).thenReturn(Optional.of(
                TestPricing.pricingRow(1L, 1, LoanMath.DHANNOW_SEED_PRICING, ConfigStatus.ACTIVE)));
        when(productRepo.findById(1L)).thenReturn(Optional.of(TestPricing.seedProductRow(1L, ConfigStatus.ACTIVE)));
        // 20:00 IST on 1 Nov, after the 18:00 same-day cutoff: still day 0 if the transfer is confirmed now.
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T14:30:00Z"), ZoneId.of("Asia/Kolkata"));
        loanService = new LoanService(loanRepository, new PricingService(pricingRepo, productRepo), clock);

        LoanApplication app = new LoanApplication();
        app.setCustomerId(7L);
        app.setAmountRequested(100_000L);                         // ₹1,000
        app.setSalaryCreditDay(30);                               // ignored on UPFRONT
        app.setApprovedRepaymentDate(LocalDate.of(2026, 11, 10)); // sanction-time projection, stale
        app.setPricingVersionId(1L);
        app.setProductVersionId(1L);
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Loan loan = loanService.disburse(app, null, "UTR-DN-1");

        assertThat(loan.getDisbursedOn()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(loan.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(loan.getDueAt()).isEqualTo(Instant.parse("2026-11-15T18:29:59Z"));
        assertThat(loan.getTenureDays()).isEqualTo(14);
        assertThat(loan.getProcessingFee()).isEqualTo(10_000L);
        assertThat(loan.getGst()).isEqualTo(1_800L);
        assertThat(loan.getUpfrontInterestPaise()).isEqualTo(14_000L);
        assertThat(loan.getNetDisbursed()).isEqualTo(74_200L);
        assertThat(loan.getTotalRepayable()).isEqualTo(100_000L);
        assertThat(loan.getOutstanding()).isEqualTo(100_000L);
        assertThat(loan.getGraceDays()).isZero();
        assertThat(loan.getPricingVersionId()).isEqualTo(1L);
        assertThat(loan.getProductVersionId()).isEqualTo(1L);
    }

    /**
     * CC-01, PLAN Phase 4: the UTR goes in on 1 Nov, the Accountant confirms on 3 Nov (a weekend, an
     * accountant delay). Day 0 is 3 Nov, so the loan is due 17 Nov 23:59:59 IST, and only now ACTIVE.
     */
    @Test
    @DisplayName("CC-01: the due date is fixed from the confirmed date, and the loan goes ACTIVE then")
    void cc01_confirmationOnALaterDayMovesDayZeroToTheConfirmedDate() {
        PricingConfigRepository pricingRepo = mock(PricingConfigRepository.class);
        ProductConfigRepository productRepo = mock(ProductConfigRepository.class);
        when(pricingRepo.findById(1L)).thenReturn(Optional.of(
                TestPricing.pricingRow(1L, 1, LoanMath.DHANNOW_SEED_PRICING, ConfigStatus.ACTIVE)));
        when(productRepo.findById(1L)).thenReturn(Optional.of(TestPricing.seedProductRow(1L, ConfigStatus.ACTIVE)));
        PricingService pricing = new PricingService(pricingRepo, productRepo);
        LoanApplication app = new LoanApplication();
        app.setCustomerId(7L);
        app.setAmountRequested(100_000L);
        app.setPricingVersionId(1L);
        app.setProductVersionId(1L);
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Clock utrDay = Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), ZoneId.of("Asia/Kolkata"));
        Loan loan = new LoanService(loanRepository, pricing, utrDay).disburse(app, null, "UTR-DN-2");
        assertThat(loan.getLifecycleStatus()).isEqualTo(LoanLifecycle.DISBURSEMENT_PENDING);
        assertThat(loan.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 15)); // indicative only
        loan.setId(5L);
        when(loanRepository.findById(5L)).thenReturn(Optional.of(loan));

        // 00:30 IST on 3 Nov is still 2 Nov in UTC: day 0 is the IST date.
        Clock confirmDay = Clock.fixed(Instant.parse("2026-11-02T19:00:00Z"), ZoneId.of("Asia/Kolkata"));
        Loan active = new LoanService(loanRepository, pricing, confirmDay).confirmDisbursal(5L, app, null);

        assertThat(active.getLifecycleStatus()).isEqualTo(LoanLifecycle.ACTIVE);
        assertThat(active.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(active.getDisbursedOn()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(active.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(active.getDueAt()).isEqualTo(Instant.parse("2026-11-17T18:29:59Z"));
        assertThat(active.getTenureDays()).isEqualTo(14);
        assertThat(active.getNetDisbursed()).isEqualTo(74_200L);
        assertThat(active.getTotalRepayable()).isEqualTo(100_000L);
        assertThat(active.getDisbursalTxnRef()).isEqualTo("UTR-DN-2");
    }

    @Test
    void onlyAnUnconfirmedLoanCanBeDiscarded() {
        Loan live = new Loan();
        live.setId(6L);
        when(loanRepository.findById(6L)).thenReturn(Optional.of(live));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loanService.discardPending(6L))
                .hasFieldOrPropertyWithValue("code", "ILLEGAL_TRANSITION");

        Loan pending = new Loan();
        pending.startPending();
        when(loanRepository.findById(7L)).thenReturn(Optional.of(pending));
        loanService.discardPending(7L);
        org.mockito.Mockito.verify(loanRepository).delete(pending);
    }
}
