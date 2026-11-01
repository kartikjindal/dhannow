package com.navix.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.notification.event.PaymentReminderEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.LoanHold;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.service.ApplicationFlowService;
import com.navix.loan.service.LoanLifecycleService;
import com.navix.loan.service.RepaymentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN Phase 3 on Flyway-migrated Postgres (V97/V98): the lifecycle job on a movable IST clock, the
 * dispute pause, the fraud block, and two schedulers at once.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class LoanLifecycleIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The app-wide Clock, movable by the test. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        void set(LocalDateTime ist) {
            now = ist.atZone(IST).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return IST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired MutableClock clock;
    @Autowired LoanRepository loans;
    @Autowired LoanLifecycleScheduler scheduler;
    @Autowired LoanLifecycleService lifecycle;
    @Autowired SchedulerLock locks;
    @Autowired RepaymentService repaymentService;
    @Autowired LoanDirectory loanDirectory;
    @Autowired ApplicationFlowService flow;
    @Autowired JdbcTemplate jdbc;

    /** A ₹1,000 DhanNow loan on the V92/V93 seed versions, disbursed 14 days before {@code due}. */
    private Loan loan(long customerId, LocalDate due) {
        Loan l = new Loan();
        l.setCustomerId(customerId);
        l.setPrincipal(100_000L);
        l.setProcessingFee(10_000L);
        l.setGst(1_800L);
        l.setNetDisbursed(74_200L);
        l.setUpfrontInterestPaise(14_000L);
        l.setDisbursedOn(due.minusDays(14));
        l.setDueDate(due);
        l.setDueAt(due.atTime(23, 59, 59).atZone(IST).toInstant());
        l.setTenureDays(14);
        l.setGraceDays(0);
        l.setPricingVersionId(1L);
        l.setProductVersionId(1L);
        l.setTotalRepayable(100_000L);
        l.setOutstanding(100_000L);
        l.setStatus(LoanStatus.ACTIVE);
        return loans.save(l);
    }

    private Loan reload(Loan l) {
        return loans.findById(l.getId()).orElseThrow();
    }

    private LoanLifecycle sweepAt(Loan l, LocalDateTime ist) {
        clock.set(ist);
        scheduler.sweep();
        return reload(l).getLifecycleStatus();
    }

    @Test
    void clockStepsDay13ToReview() {
        LocalDate due = LocalDate.of(2027, 1, 15);
        Loan l = loan(301L, due);

        assertThat(sweepAt(l, due.minusDays(1).atTime(10, 0))).isEqualTo(LoanLifecycle.ACTIVE);
        assertThat(sweepAt(l, due.atStartOfDay())).isEqualTo(LoanLifecycle.DUE_TODAY);
        assertThat(sweepAt(l, due.atTime(23, 59, 59))).isEqualTo(LoanLifecycle.DUE_TODAY);
        assertThat(sweepAt(l, due.plusDays(1).atStartOfDay())).isEqualTo(LoanLifecycle.OVERDUE_D1);
        assertThat(reload(l).getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(sweepAt(l, due.plusDays(6).atTime(23, 0))).isEqualTo(LoanLifecycle.OVERDUE_D1);
        assertThat(sweepAt(l, due.plusDays(7).atTime(0, 5))).isEqualTo(LoanLifecycle.OVERDUE_REVIEW);
        // markInCollections kept the navix status in step.
        assertThat(reload(l).getStatus()).isEqualTo(LoanStatus.IN_COLLECTIONS);
        assertThat(sweepAt(l, due.plusDays(8).atTime(9, 0))).isEqualTo(LoanLifecycle.OVERDUE_REVIEW);
    }

    /** CC-13: the job was down from day 13 to day 9 overdue; one run lands the loan in review. */
    @Test
    void cc13_schedulerDownForDaysCatchesUpFromTheClock() {
        LocalDate due = LocalDate.of(2027, 2, 15);
        Loan l = loan(302L, due);

        assertThat(sweepAt(l, due.plusDays(9).atTime(14, 0))).isEqualTo(LoanLifecycle.OVERDUE_REVIEW);
        assertThat(reload(l).getStatus()).isEqualTo(LoanStatus.IN_COLLECTIONS);
    }

    /** CC-14: two instances at once move the loan once; the job lease admits one runner. */
    @Test
    void cc14_twoSchedulersAtOnceMakeASingleTransition() throws Exception {
        LocalDate due = LocalDate.of(2027, 3, 15);
        Loan l = loan(303L, due);
        clock.set(due.plusDays(1).atTime(9, 0));

        List<Boolean> moved = both(() -> lifecycle.advance(l.getId()));
        assertThat(moved).containsExactlyInAnyOrder(true, false);
        assertThat(reload(l).getLifecycleStatus()).isEqualTo(LoanLifecycle.OVERDUE_D1);

        AtomicInteger runs = new AtomicInteger();
        List<Boolean> ran = both(() -> locks.runExclusive("cc14-test", Duration.ZERO, Duration.ofMinutes(1), () -> {
            runs.incrementAndGet();
            sleep(500);
        }));
        assertThat(ran).containsExactlyInAnyOrder(true, false);
        assertThat(runs).hasValue(1);
        // Released (atLeast 0): the next run takes it again.
        assertThat(locks.runExclusive("cc14-test", Duration.ZERO, Duration.ofMinutes(1), runs::incrementAndGet)).isTrue();
    }

    /** A DISPUTED hold pauses reminders and drops the loan off the collections worklist (and NACH). */
    @Test
    void disputePausesRemindersAndCollections() {
        LocalDate due = LocalDate.of(2027, 4, 15);
        Loan disputed = loan(304L, due);
        Loan open = loan(305L, due);
        disputed.setHoldStatus(LoanHold.DISPUTED);
        loans.save(disputed);
        clock.set(due.plusDays(2).atTime(9, 0));
        scheduler.sweep();

        List<Object> published = new ArrayList<>();
        new PaymentReminderScheduler(loans, repaymentService, published::add, clock, locks).sendDueAndOverdueReminders();

        assertThat(published).filteredOn(PaymentReminderEvent.class::isInstance)
                .extracting(e -> ((PaymentReminderEvent) e).loanId())
                .contains(open.getId()).doesNotContain(disputed.getId());
        assertThat(loanDirectory.listCollectible(due.plusDays(2))).extracting(LoanSummary::loanId)
                .contains(open.getId()).doesNotContain(disputed.getId());
        // The hold does not stop the clock: the loan is still overdue.
        assertThat(reload(disputed).getLifecycleStatus()).isEqualTo(LoanLifecycle.OVERDUE_D1);
    }

    @Test
    void fraudReviewBlocksANewApplication() {
        Loan l = loan(306L, LocalDate.of(2027, 5, 15));
        l.setHoldStatus(LoanHold.FRAUD_REVIEW);
        loans.save(l);
        ActorContext.set(new CurrentActor("306", "Borrower 306", "BORROWER"));
        try {
            assertThatThrownBy(() -> flow.createDraft(306L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo("FRAUD_REVIEW");
        } finally {
            ActorContext.clear();
        }
    }

    /** V97: the new columns are checked, and loan_document takes the Phase 5-6 types. */
    @Test
    void v97ConstraintsHold() {
        Loan l = loan(307L, LocalDate.of(2027, 6, 15));
        assertThatThrownBy(() -> jdbc.update("update loan set hold_status = 'FROZEN' where id = ?", l.getId()))
                .hasMessageContaining("loan_status_check");
        assertThatThrownBy(() -> jdbc.update("update loan set lifecycle_status = 'OVERDUE' where id = ?", l.getId()))
                .hasMessageContaining("loan_status_check");
        jdbc.update("insert into loan_document (loan_id, type) values (?, 'SCHEDULE')", l.getId());
        assertThat(jdbc.queryForObject("select overdue_review_dpd from product_config where version = 1", Integer.class))
                .isEqualTo(7);
    }

    private static <T> List<T> both(Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
