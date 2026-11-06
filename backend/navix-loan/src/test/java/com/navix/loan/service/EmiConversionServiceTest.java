package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.collections.ExtensionDirectory;
import com.navix.common.collections.SettlementDirectory;
import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.idempotency.IdempotencyService;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.ProductSnapshot;
import com.navix.loan.dto.EmiDtos.EmiRequest;
import com.navix.loan.entity.EmiConversion;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.EmiConversionRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.MandateDebitRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.service.RepaymentScheduleService.Instalment;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** PLAN Phase 6c: recovery EMI conversion rules (the full path is in ApplicationFlowIntegrationTest). */
class EmiConversionServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Clock CLOCK = Clock.fixed(TODAY.atTime(12, 0).atZone(IST).toInstant(), IST);
    private static final ProductSnapshot PRODUCT = ProductSnapshot.SEED;

    private final EmiConversionRepository emis = mock(EmiConversionRepository.class);
    private final LoanRepository loans = mock(LoanRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final MandateDebitRepository debits = mock(MandateDebitRepository.class);
    private final PricingService pricing = mock(PricingService.class);
    private final LoanExtensionLedger extensionLedger = mock(LoanExtensionLedger.class);
    private final ExtensionDirectory extensions = mock(ExtensionDirectory.class);
    private final SettlementDirectory settlements = mock(SettlementDirectory.class);
    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final EmiConversionService service = new EmiConversionService(emis, loans, payments, debits, pricing,
            mock(RepaymentService.class), mock(RepaymentScheduleService.class), extensionLedger, extensions, settlements,
            mock(LoanDirectory.class), flags, mock(OtpVerifierPort.class), mock(IdempotencyService.class),
            mock(JdbcTemplate.class), new ObjectMapper(), CLOCK, mock(ComplaintService.class));

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    /** CC-20: ₹7,000 in three instalments sums exactly; the remainder paise go to the last; days 10/20/30. */
    @Test
    void cc20_sevenThousandInThreeInstalmentsSumsExactly() {
        List<Instalment> plan = EmiConversionService.plan(TODAY, 700_000L, 0L, 3, 10, PRODUCT);
        assertThat(plan).extracting(Instalment::principalPaise).containsExactly(233_333L, 233_333L, 233_334L);
        assertThat(plan.stream().mapToLong(Instalment::totalPaise).sum()).isEqualTo(700_000L);
        assertThat(plan).extracting(Instalment::dueDate)
                .containsExactly(TODAY.plusDays(10), TODAY.plusDays(20), TODAY.plusDays(30));
        assertThat(plan.get(0).dueAt()).isEqualTo(LoanMath.dueAt(TODAY.plusDays(10), PRODUCT));
    }

    /** CC-20 property: every ladder amount, with any late charge, splits into instalments that sum exactly. */
    @Test
    void cc20_everyLadderAmountAndChargeSumsExactly() {
        for (long amount : PRODUCT.allowedAmountsPaise()) {
            int n = PRODUCT.emiInstalments(amount);
            for (long c = 0; c <= 60_000L; c += 1_337L) {
                long charges = c;
                List<Instalment> plan = EmiConversionService.plan(TODAY, amount, charges, n, PRODUCT.emiGapDays(), PRODUCT);
                assertThat(plan).hasSize(n);
                assertThat(plan.stream().mapToLong(Instalment::principalPaise).sum()).isEqualTo(amount);
                assertThat(plan.stream().mapToLong(Instalment::penalPaise).sum()).isEqualTo(charges);
                assertThat(plan).allMatch(i -> i.principalPaise() >= amount / n && i.penalPaise() >= charges / n);
            }
        }
    }

    /** Up to ₹5,000: two instalments (days 10/20); ₹7,000 and ₹10,000: three, from product_config. */
    @Test
    void theInstalmentCountComesFromTheLadderConfig() {
        assertThat(PRODUCT.allowedAmountsPaise()).allMatch(a -> PRODUCT.emiInstalments(a) == (a <= 500_000L ? 2 : 3));
        ProductSnapshot four = new ProductSnapshot(9L, PRODUCT.allowedAmountsPaise(), PRODUCT.minPaise(),
                PRODUCT.maxPaise(), PRODUCT.dueCutoff(), PRODUCT.zone(), 7, 3, 3, 15, 400_000L);
        assertThat(four.emiInstalments(400_000L)).isEqualTo(3);
        assertThat(four.emiInstalments(300_000L)).isEqualTo(2);
    }

    @Test
    void anEmiApprovedByANonAdminIs422() {
        ActorContext.set(new CurrentActor("8", "Head", "COLLECTION_HEAD"));
        assertThatThrownBy(() -> service.approve(UUID.randomUUID()))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("FORBIDDEN_ROLE");
        ActorContext.set(new CurrentActor("3", "Accountant", "ACCOUNTANT"));
        assertThatThrownBy(() -> service.reject(UUID.randomUUID(), "OTHER", null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("FORBIDDEN_ROLE");
        verifyNoInteractions(emis);
    }

    @Test
    void theRequesterCannotApprove() {
        EmiConversion e = requested(5L);
        ActorContext.set(new CurrentActor("5", "Admin", "ADMIN"));
        assertThatThrownBy(() -> service.approve(e.getId()))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("SOD_VIOLATION");
        assertThatThrownBy(() -> service.reject(e.getId(), "OTHER", null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("SOD_VIOLATION");
        assertThat(e.getStatus().name()).isEqualTo("REQUESTED");
    }

    @Test
    void aRequestIsRefusedWhileTheFlagIsOff() {
        ActorContext.set(new CurrentActor("7", "Officer", "COLLECTION_EXECUTIVE"));
        when(flags.isEnabled("emi-conversion", false)).thenReturn(false);
        assertThatThrownBy(() -> service.request(1L, new EmiRequest(null, null)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("FEATURE_DISABLED");
        verify(emis, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void onlyCollectionsRequests() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        assertThatThrownBy(() -> service.request(1L, null))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("FORBIDDEN_ROLE");
    }

    /** Refused while an extension is open (and an extension is refused while a conversion is open: the ledger's EMI_OPEN). */
    @Test
    void aConversionNeedsAnOverdueLoanWithNoExtensionOpen() {
        Loan loan = new Loan();
        loan.setId(1L);
        loan.setPrincipal(700_000L);
        when(flags.isEnabled("emi-conversion", false)).thenReturn(true);
        when(pricing.product(any())).thenReturn(PRODUCT);
        when(payments.findByLoanId(1L)).thenReturn(List.of());
        when(settlements.approvedSettlementAmount(1L)).thenReturn(Optional.empty());

        when(extensionLedger.effectiveState(loan, PRODUCT)).thenReturn(LoanLifecycle.DUE_TODAY);
        assertThat(service.ineligibleReason(loan)).isEqualTo("NOT_OVERDUE");

        when(extensionLedger.effectiveState(loan, PRODUCT)).thenReturn(LoanLifecycle.OVERDUE_D1);
        assertThat(service.ineligibleReason(loan)).isNull();

        when(extensions.hasOpen(1L)).thenReturn(true);
        assertThat(service.ineligibleReason(loan)).isEqualTo("EXTENSION_OPEN");

        when(extensions.hasOpen(1L)).thenReturn(false);
        when(extensionLedger.effectiveState(loan, PRODUCT)).thenReturn(LoanLifecycle.RESTRUCTURED);
        assertThat(service.ineligibleReason(loan)).isEqualTo("RESTRUCTURED");
    }

    private EmiConversion requested(long by) {
        EmiConversion e = new EmiConversion();
        e.setId(UUID.randomUUID());
        e.setLoanId(1L);
        e.setRequestedBy(by);
        e.setInstalmentCount(3);
        e.setGapDays(10);
        e.setAcceptBy(TODAY.plusDays(3));
        e.setExpiresAt(TODAY.plusDays(3).atTime(23, 59, 59).atZone(IST).toInstant());
        when(emis.findById(e.getId())).thenReturn(Optional.of(e));
        return e;
    }
}
