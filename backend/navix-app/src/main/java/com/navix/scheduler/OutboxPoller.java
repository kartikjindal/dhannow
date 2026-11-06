package com.navix.scheduler;

import com.navix.common.outbox.Outbox;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute, retries the outbox rows that are due (PLAN Phase 9, EXT-22). One instance at a time. */
@Component
public class OutboxPoller {

    private final Outbox outbox;
    private final SchedulerLock locks;

    public OutboxPoller(Outbox outbox, SchedulerLock locks) {
        this.outbox = outbox;
        this.locks = locks;
    }

    @Scheduled(cron = "${navix.outbox.cron:30 * * * * *}", zone = "Asia/Kolkata")
    public void poll() {
        locks.runExclusive("outbox", Duration.ZERO, Duration.ofMinutes(10), () -> outbox.runDue(100));
    }
}
