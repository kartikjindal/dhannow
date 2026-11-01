package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.domain.CreditLevelReason;
import com.navix.loan.domain.ProductSnapshot;
import com.navix.loan.entity.CreditLevel;
import com.navix.loan.entity.CreditLevelHistory;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.CreditLevelHistoryRepository;
import com.navix.loan.repository.CreditLevelRepository;
import com.navix.loan.repository.PricingConfigRepository;
import com.navix.loan.repository.ProductConfigRepository;
import com.navix.loan.service.CreditLevelService.CreditLevelView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The credit ladder rules (PLAN Phase 2), on the V93 seed ladder ₹1k → 2k → 3k → 4k → 5k → 7k → 10k. */
@ExtendWith(MockitoExtension.class)
class CreditLevelServiceTest {

    private static final Long CUSTOMER = 7L;
    private static final LocalDate DUE = LocalDate.of(2026, 11, 15);
    private static final Instant DUE_AT = LoanMath.dueAt(DUE, ProductSnapshot.SEED); // 23:59:59 IST

    @Mock
    private CreditLevelRepository levels;
    @Mock
    private CreditLevelHistoryRepository history;
    @Mock
    private PricingConfigRepository pricingRepo;
    @Mock
    private ProductConfigRepository productRepo;

    private CreditLevelService service;

    @BeforeEach
    void setUp() {
        lenient().when(productRepo.findFirstByStatus(ConfigStatus.ACTIVE))
                .thenReturn(Optional.of(TestPricing.seedProductRow(1L, ConfigStatus.ACTIVE)));
        service = new CreditLevelService(levels, history, new PricingService(pricingRepo, productRepo),
                Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneId.of("Asia/Kolkata")));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private CreditLevel at(int level) {
        CreditLevel row = new CreditLevel();
        row.setCustomerId(CUSTOMER);
        row.setLevel((short) level);
        when(levels.findById(CUSTOMER)).thenReturn(Optional.of(row));
        return row;
    }

    private static Loan loan() {
        Loan loan = new Loan();
        loan.setId(50L);
        loan.setCustomerId(CUSTOMER);
        loan.setDueDate(DUE);
        loan.setDueAt(DUE_AT);
        return loan;
    }

    private CreditLevelHistory lastHistory() {
        ArgumentCaptor<CreditLevelHistory> row = ArgumentCaptor.forClass(CreditLevelHistory.class);
        verify(history).save(row.capture());
        return row.getValue();
    }

    @Test
    void aNewCustomerIsLevelOneWithTheWholeLadder() {
        CreditLevelView v = service.view(CUSTOMER);

        assertThat(v.level()).isEqualTo(1);
        assertThat(v.maxPaise()).isEqualTo(100_000L);
        assertThat(v.nextLevelPaise()).isEqualTo(200_000L);
        assertThat(v.ladder()).containsExactly(100_000L, 200_000L, 300_000L, 400_000L, 500_000L, 700_000L, 1_000_000L);
    }

    @Test
    void theFirstDisbursalRecordsLevelOneOnce() {
        service.onLoanDisbursed(CUSTOMER, 50L);

        CreditLevelHistory h = lastHistory();
        assertThat(h.getReason()).isEqualTo(CreditLevelReason.FIRST_LOAN);
        assertThat(h.getFromLevel()).isNull();
        assertThat(h.getToLevel()).isEqualTo((short) 1);
        assertThat(h.getLoanId()).isEqualTo(50L);

        when(levels.existsById(CUSTOMER)).thenReturn(true);
        service.onLoanDisbursed(CUSTOMER, 51L);
        verify(history).save(any()); // still only the first
    }

    @Test
    void onTimeRepaymentMovesUpOneLevel() {
        CreditLevel row = at(1);

        service.onLoanClosed(loan(), DUE_AT, false); // paid at the last second of day 14

        assertThat(row.getLevel()).isEqualTo((short) 2);
        assertThat(row.getMaxPaise()).isEqualTo(200_000L);
        CreditLevelHistory h = lastHistory();
        assertThat(h.getReason()).isEqualTo(CreditLevelReason.ON_TIME_REPAID);
        assertThat(h.getFromLevel()).isEqualTo((short) 1);
        assertThat(h.getToLevel()).isEqualTo((short) 2);
        assertThat(h.getLoanId()).isEqualTo(50L);
    }

    @Test
    void oneMinuteLateKeepsTheLevel() {
        CreditLevel row = at(3);

        service.onLoanClosed(loan(), DUE_AT.plus(Duration.ofMinutes(1)), false);

        assertThat(row.getLevel()).isEqualTo((short) 3);
        assertThat(lastHistory().getReason()).isEqualTo(CreditLevelReason.LATE_REPAID_NO_CHANGE);
    }

    @Test
    void levelSevenStaysAtLevelSeven() {
        CreditLevel row = at(7);

        service.onLoanClosed(loan(), DUE_AT.minus(Duration.ofDays(3)), false);

        assertThat(row.getLevel()).isEqualTo((short) 7);
        assertThat(row.getMaxPaise()).isEqualTo(1_000_000L);
        assertThat(service.view(CUSTOMER).nextLevelPaise()).isNull();
    }

    @Test
    void aSettlementNeverRaisesTheLevel() {
        CreditLevel row = at(2);

        service.onLoanClosed(loan(), DUE_AT.minus(Duration.ofDays(1)), true);

        assertThat(row.getLevel()).isEqualTo((short) 2);
        CreditLevelHistory h = lastHistory();
        assertThat(h.getReason()).isEqualTo(CreditLevelReason.LATE_REPAID_NO_CHANGE);
        assertThat(h.getReasonCode()).isEqualTo("SETTLED");
    }

    @Test
    void aNavixLoanWithoutDueAtIsDueAtTheEndOfItsDueDate() {
        CreditLevel row = at(1);
        Loan loan = loan();
        loan.setDueAt(null);

        service.onLoanClosed(loan, DUE_AT, false);

        assertThat(row.getLevel()).isEqualTo((short) 2);
    }

    @Test
    void manualExceptionNeedsAdmin() {
        ActorContext.set(new CurrentActor("5", "Head", "CREDIT_HEAD"));

        assertThatThrownBy(() -> service.manualException(CUSTOMER, 4, "RISK_REVIEW"))
                .hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
        verify(levels, never()).save(any());
    }

    @Test
    void manualExceptionNeedsAReason() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));

        assertThatThrownBy(() -> service.manualException(CUSTOMER, 4, " "))
                .hasFieldOrPropertyWithValue("code", "REASON_REQUIRED");
        assertThatThrownBy(() -> service.manualException(CUSTOMER, 8, "RISK_REVIEW"))
                .hasFieldOrPropertyWithValue("code", "INVALID_LEVEL");
        verify(levels, never()).save(any());
    }

    @Test
    void manualExceptionByAdminIsRecordedWithTheApprover() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        CreditLevel row = at(1);

        CreditLevelView v = service.manualException(CUSTOMER, 4, "RISK_REVIEW");

        assertThat(row.getLevel()).isEqualTo((short) 4);
        assertThat(v.maxPaise()).isEqualTo(400_000L);
        CreditLevelHistory h = lastHistory();
        assertThat(h.getReason()).isEqualTo(CreditLevelReason.MANUAL_EXCEPTION);
        assertThat(h.getApprovedBy()).isEqualTo(1L);
        assertThat(h.getReasonCode()).isEqualTo("RISK_REVIEW");
        assertThat(h.getAt()).isEqualTo(Instant.parse("2026-11-15T10:00:00Z"));
    }

    @Test
    void aSanctionMustBeALadderAmountWithinTheLevel() {
        at(2);

        service.requireSanctionable(CUSTOMER, 200_000L);
        service.requireSanctionable(CUSTOMER, 100_000L);
        assertThatThrownBy(() -> service.requireSanctionable(CUSTOMER, 300_000L))
                .hasFieldOrPropertyWithValue("code", "LIMIT_EXCEEDED");
        assertThatThrownBy(() -> service.requireSanctionable(CUSTOMER, 150_000L))
                .hasFieldOrPropertyWithValue("code", "AMOUNT_NOT_ALLOWED");
    }

    @Test
    void theBorrowerReadsTheirOwnLevel() {
        ActorContext.set(new CurrentActor("7", "Asha", "BORROWER"));
        at(5);

        CreditLevelView v = service.mine();

        assertThat(v.level()).isEqualTo(5);
        assertThat(v.maxPaise()).isEqualTo(500_000L);
        assertThat(v.nextLevelPaise()).isEqualTo(700_000L);
    }

    @Test
    void staffCannotReadTheBorrowerEndpoint() {
        ActorContext.set(new CurrentActor("5", "Head", "CREDIT_HEAD"));

        assertThatThrownBy(() -> service.mine()).hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
    }

    @Test
    void aLevelAboveAShortenedLadderReadsAsItsTop() {
        com.navix.loan.entity.ProductConfig shorter = TestPricing.seedProductRow(2L, ConfigStatus.ACTIVE);
        shorter.setAllowedAmountsPaise(List.of(100_000L, 200_000L).toArray(Long[]::new));
        shorter.setMaxPaise(200_000L);
        when(productRepo.findFirstByStatus(ConfigStatus.ACTIVE)).thenReturn(Optional.of(shorter));
        at(5);

        assertThat(service.maxPaise(CUSTOMER)).isEqualTo(200_000L);
        assertThat(service.view(CUSTOMER).level()).isEqualTo(2);
    }
}
