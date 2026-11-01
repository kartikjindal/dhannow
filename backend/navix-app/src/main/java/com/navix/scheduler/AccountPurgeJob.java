package com.navix.scheduler;

import com.navix.auth.AccountDeletionService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily at 02:30 IST: blanks the identity of deleted accounts whose retention period has ended (CC-45). */
@Component
public class AccountPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeJob.class);

    private final AccountDeletionService deletions;
    private final SchedulerLock locks;

    public AccountPurgeJob(AccountDeletionService deletions, SchedulerLock locks) {
        this.deletions = deletions;
        this.locks = locks;
    }

    @Scheduled(cron = "${navix.account-deletion.purge-cron:0 30 2 * * *}", zone = "Asia/Kolkata")
    public void purge() {
        locks.runExclusive("account-purge", Duration.ofMinutes(5), Duration.ofMinutes(30), () -> {
            int n = deletions.purgeDue();
            if (n > 0) {
                log.info("Account purge: {} account(s) purged after retention", n);
            }
        });
    }
}
