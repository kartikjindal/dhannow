package com.navix.scheduler;

import com.navix.loan.service.ComplaintService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 15 minutes, flags complaint SLA milestones that passed unmet and tells the assignee and the ADMINs (PLAN
 * Phase 7). Idempotent (a milestone is flagged once) and one instance at a time ({@code scheduler_lock}).
 */
@Component
public class ComplaintSlaSweep {

    private static final Logger log = LoggerFactory.getLogger(ComplaintSlaSweep.class);

    static final String LOCK = "complaint-sla";

    private final ComplaintService complaints;
    private final SchedulerLock locks;

    public ComplaintSlaSweep(ComplaintService complaints, SchedulerLock locks) {
        this.complaints = complaints;
        this.locks = locks;
    }

    @Scheduled(cron = "${navix.complaints.sla-cron:0 */15 * * * *}", zone = "Asia/Kolkata")
    public void sweep() {
        locks.runExclusive(LOCK, Duration.ZERO, Duration.ofMinutes(10), () -> {
            int flagged = complaints.flagBreaches();
            if (flagged > 0) {
                log.info("Complaint SLA: {} milestone(s) breached", flagged);
            }
        });
    }
}
