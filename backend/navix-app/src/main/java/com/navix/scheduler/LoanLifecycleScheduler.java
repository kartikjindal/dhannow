package com.navix.scheduler;

import com.navix.loan.service.LoanLifecycleService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 5 minutes, moves open loans along the timed lifecycle (ACTIVE → DUE_TODAY → OVERDUE_D1 →
 * OVERDUE_REVIEW) as {@link LoanLifecycleService} derives it from the IST {@code Clock}. Idempotent:
 * a second run, or a second instance, finds nothing to move (CC-13, CC-14).
 */
@Component
public class LoanLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(LoanLifecycleScheduler.class);

    static final String LOCK = "loan-lifecycle";

    private final LoanLifecycleService lifecycle;
    private final SchedulerLock locks;

    public LoanLifecycleScheduler(LoanLifecycleService lifecycle, SchedulerLock locks) {
        this.lifecycle = lifecycle;
        this.locks = locks;
    }

    /** Every 5 minutes (override with {@code navix.lifecycle.cron}; "-" turns it off). */
    @Scheduled(cron = "${navix.lifecycle.cron:0 */5 * * * *}", zone = "Asia/Kolkata")
    public void sweep() {
        // atLeast 0: re-running is harmless (each move takes the loan's row lock), so release at once.
        locks.runExclusive(LOCK, Duration.ZERO, Duration.ofMinutes(4), this::advanceAll);
    }

    /** One pass over every loan the clock may move. @return how many changed state. */
    public int advanceAll() {
        int moved = 0;
        for (Long loanId : lifecycle.dueForReview()) {
            try {
                if (lifecycle.advance(loanId)) {
                    moved++;
                }
            } catch (RuntimeException e) {
                // One bad loan must not stop the rest; the next run retries it.
                log.warn("Lifecycle sweep skipped loan {}: {}", loanId, e.getMessage());
            }
        }
        if (moved > 0) {
            log.info("Lifecycle sweep: {} loan(s) moved", moved);
        }
        return moved;
    }
}
