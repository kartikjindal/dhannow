package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.loan.LoanDirectory;
import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.ProductConfig;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PricingConfigRepository;
import com.navix.loan.repository.ProductConfigRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PLAN Phase 3: the lifecycle move for one loan, on the IST clock and the frozen product config. */
class LoanLifecycleServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DUE = LocalDate.of(2026, 11, 15);

    private final LoanRepository loans = mock(LoanRepository.class);
    private final ProductConfigRepository products = mock(ProductConfigRepository.class);
    private final LoanDirectory directory = mock(LoanDirectory.class);
    private Loan loan;

    @BeforeEach
    void setUp() {
        loan = new Loan();
        loan.setId(1L);
        loan.setStatus(LoanStatus.ACTIVE);
        loan.setDueDate(DUE);
        when(loans.findByIdForUpdate(1L)).thenAnswer(i -> Optional.of(loan));
        // markInCollections, as the adapter does it: past due → OVERDUE_REVIEW.
        doAnswer(i -> loan.advanceTo(LoanLifecycle.OVERDUE_REVIEW)).when(directory).markInCollections(1L);
    }

    private LoanLifecycleService at(LocalDate day, int hour) {
        Clock clock = Clock.fixed(day.atTime(hour, 0).atZone(IST).toInstant(), IST);
        return new LoanLifecycleService(loans, new PricingService(mock(PricingConfigRepository.class), products),
                directory, clock);
    }

    @Test
    void dueOnTheDayThenOverdueAfterIt() {
        assertThat(at(DUE.minusDays(1), 12).advance(1L)).isFalse();
        assertThat(at(DUE, 0).advance(1L)).isTrue();
        assertThat(loan.getLifecycleStatus()).isEqualTo(LoanLifecycle.DUE_TODAY);
        assertThat(at(DUE, 23).advance(1L)).isFalse();
        assertThat(at(DUE.plusDays(1), 0).advance(1L)).isTrue();
        assertThat(loan.getLifecycleStatus()).isEqualTo(LoanLifecycle.OVERDUE_D1);
        verify(directory, never()).markInCollections(any());
        verify(loans, org.mockito.Mockito.times(2)).save(loan);
    }

    /** CC-13: a scheduler down for a week lands the loan in review in one run, via markInCollections. */
    @Test
    void cc13_catchUpAfterDowntimeUsesTheClockNotTheLastRun() {
        assertThat(at(DUE.plusDays(8), 9).advance(1L)).isTrue();

        assertThat(loan.getLifecycleStatus()).isEqualTo(LoanLifecycle.OVERDUE_REVIEW);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.IN_COLLECTIONS);
        verify(directory).markInCollections(1L);
        assertThat(at(DUE.plusDays(9), 9).advance(1L)).isFalse();
    }

    /** The review day is the frozen product version's overdue_review_dpd, not a constant. */
    @Test
    void reviewDayIsReadFromTheLoansProductVersion() {
        ProductConfig row = TestPricing.seedProductRow(5L, ConfigStatus.ACTIVE);
        row.setOverdueReviewDpd(3);
        when(products.findById(5L)).thenReturn(Optional.of(row));
        loan.setProductVersionId(5L);
        loan.setDueAt(DUE.atTime(23, 59, 59).atZone(IST).toInstant());

        at(DUE.plusDays(3), 9).advance(1L);

        assertThat(loan.getLifecycleStatus()).isEqualTo(LoanLifecycle.OVERDUE_REVIEW);
    }

    @Test
    void closedOrMissingLoanIsLeftAlone() {
        loan.moveTo(LoanLifecycle.CLOSED);
        assertThat(at(DUE.plusDays(8), 9).advance(1L)).isFalse();
        assertThat(at(DUE.plusDays(8), 9).advance(2L)).isFalse();
        verify(loans, never()).save(any());
    }

    @Test
    void dueForReviewAsksForTimedStatesDueByTodayIst() {
        when(loans.findIdsForLifecycleSweep(LoanLifecycleService.TIMED, DUE)).thenReturn(List.of(1L));
        assertThat(at(DUE, 1).dueForReview()).containsExactly(1L);
    }
}
