package com.navix.loan.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import com.navix.loan.entity.Loan;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** PLAN Phase 3: the loan lifecycle map, the clock rule and the navix status kept in step. */
class LoanLifecycleTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DUE = LocalDate.of(2026, 11, 15);
    private static final Instant DUE_AT = DUE.atTime(23, 59, 59).atZone(IST).toInstant();

    private static Instant ist(LocalDateTime t) {
        return t.atZone(IST).toInstant();
    }

    private static LoanLifecycle at(LocalDateTime t) {
        return LoanLifecycle.forTime(ist(t), DUE_AT, IST, 7);
    }

    @Test
    void clockWalksDay13ToReview() {
        assertThat(at(DUE.minusDays(1).atTime(12, 0))).isEqualTo(LoanLifecycle.ACTIVE);
        assertThat(at(DUE.minusDays(1).atTime(23, 59, 59))).isEqualTo(LoanLifecycle.ACTIVE);
        assertThat(at(DUE.atStartOfDay())).isEqualTo(LoanLifecycle.DUE_TODAY);
        assertThat(at(DUE.atTime(23, 59, 59))).isEqualTo(LoanLifecycle.DUE_TODAY);
        assertThat(at(DUE.plusDays(1).atStartOfDay())).isEqualTo(LoanLifecycle.OVERDUE_D1);
        assertThat(at(DUE.plusDays(6).atTime(23, 59))).isEqualTo(LoanLifecycle.OVERDUE_D1);
        assertThat(at(DUE.plusDays(7).atStartOfDay())).isEqualTo(LoanLifecycle.OVERDUE_REVIEW);
    }

    @Test
    void reviewDayComesFromConfig() {
        assertThat(LoanLifecycle.forTime(ist(DUE.plusDays(3).atTime(9, 0)), DUE_AT, IST, 3))
                .isEqualTo(LoanLifecycle.OVERDUE_REVIEW);
        assertThat(LoanLifecycle.forTime(ist(DUE.plusDays(3).atTime(9, 0)), DUE_AT, IST, 4))
                .isEqualTo(LoanLifecycle.OVERDUE_D1);
    }

    @Test
    void transitionMap() {
        assertThat(LoanLifecycle.DISBURSEMENT_PENDING.canTransitionTo(LoanLifecycle.ACTIVE)).isTrue();
        assertThat(LoanLifecycle.ACTIVE.canTransitionTo(LoanLifecycle.DUE_TODAY)).isTrue();
        assertThat(LoanLifecycle.ACTIVE.canTransitionTo(LoanLifecycle.OVERDUE_D1)).isFalse();
        assertThat(LoanLifecycle.DUE_TODAY.canTransitionTo(LoanLifecycle.OVERDUE_D1)).isTrue();
        assertThat(LoanLifecycle.OVERDUE_D1.canTransitionTo(LoanLifecycle.OVERDUE_REVIEW)).isTrue();
        assertThat(LoanLifecycle.OVERDUE_REVIEW.canTransitionTo(LoanLifecycle.RESTRUCTURE_PENDING)).isTrue();
        assertThat(LoanLifecycle.RESTRUCTURE_PENDING.canTransitionTo(LoanLifecycle.RESTRUCTURED)).isTrue();
        assertThat(LoanLifecycle.OVERDUE_REVIEW.canTransitionTo(LoanLifecycle.ACTIVE)).isFalse();
        assertThat(LoanLifecycle.DISBURSEMENT_PENDING.canTransitionTo(LoanLifecycle.CLOSED)).isFalse();
        // Any disbursed state closes; CLOSED is terminal.
        for (LoanLifecycle s : new LoanLifecycle[] {LoanLifecycle.ACTIVE, LoanLifecycle.DUE_TODAY,
                LoanLifecycle.OVERDUE_D1, LoanLifecycle.OVERDUE_REVIEW, LoanLifecycle.RESTRUCTURE_PENDING,
                LoanLifecycle.RESTRUCTURED}) {
            assertThat(s.canTransitionTo(LoanLifecycle.CLOSED)).as(s.name()).isTrue();
        }
        for (LoanLifecycle s : LoanLifecycle.values()) {
            assertThat(LoanLifecycle.CLOSED.canTransitionTo(s)).isFalse();
        }
    }

    @Test
    void illegalMoveIsRejectedAndNavixStatusFollows() {
        Loan loan = new Loan();
        assertThatThrownBy(() -> loan.moveTo(LoanLifecycle.OVERDUE_REVIEW))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ILLEGAL_TRANSITION");
        assertThat(loan.moveTo(LoanLifecycle.ACTIVE)).isFalse();

        assertThat(loan.moveTo(LoanLifecycle.DUE_TODAY)).isTrue();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        loan.moveTo(LoanLifecycle.CLOSED);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CLOSED);
        assertThatThrownBy(() -> loan.moveTo(LoanLifecycle.ACTIVE)).isInstanceOf(BusinessException.class);
    }

    /** CC-13: days of missed runs still step through every state, and never move backwards. */
    @Test
    void cc13_advanceCatchesUpThroughEveryState() {
        Loan loan = new Loan();
        assertThat(loan.advanceTo(LoanLifecycle.OVERDUE_REVIEW)).isTrue();
        assertThat(loan.getLifecycleStatus()).isEqualTo(LoanLifecycle.OVERDUE_REVIEW);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.IN_COLLECTIONS);
        assertThat(loan.advanceTo(LoanLifecycle.DUE_TODAY)).isFalse();
        assertThat(loan.getLifecycleStatus()).isEqualTo(LoanLifecycle.OVERDUE_REVIEW);
    }

    @Test
    void presentmentPausedOnHoldPendingPaymentOrRestructure() {
        Loan loan = new Loan();
        assertThat(loan.presentmentPaused()).isFalse();
        loan.setHoldStatus(LoanHold.FRAUD_REVIEW);
        assertThat(loan.presentmentPaused()).isFalse();
        loan.setHoldStatus(LoanHold.DISPUTED);
        assertThat(loan.presentmentPaused()).isTrue();
        loan.setHoldStatus(LoanHold.NONE);
        loan.setRepaymentStatus(RepaymentStatus.PAID_PENDING_VERIFICATION);
        assertThat(loan.presentmentPaused()).isTrue();
    }
}
