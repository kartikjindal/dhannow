package com.navix.scheduler;

import com.navix.collections.service.LoanExtensionService;
import com.navix.loan.service.EmiConversionService;
import com.navix.loan.service.RepaymentScheduleService;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * At 00:05 IST, expires extension offers whose pay-by day has ended (PLAN Phase 6b, CC-67, EXT-29) and EMI offers
 * whose accept-by day has ended (Phase 6c), and marks instalments past their due time OVERDUE (CC-17). Five
 * minutes after the boundary so no request races it; reads already treat a lapsed offer as expired. A payment
 * already on file (PAYMENT_PENDING) is never swept. Idempotent, one instance at a time.
 */
@Component
public class ExtensionExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(ExtensionExpirySweep.class);

    static final String LOCK = "extension-expiry";

    private final LoanExtensionService extensions;
    private final EmiConversionService emis;
    private final RepaymentScheduleService schedules;
    private final SchedulerLock locks;
    private final Clock clock;

    public ExtensionExpirySweep(LoanExtensionService extensions, EmiConversionService emis,
                                RepaymentScheduleService schedules, SchedulerLock locks, Clock clock) {
        this.extensions = extensions;
        this.emis = emis;
        this.schedules = schedules;
        this.locks = locks;
        this.clock = clock;
    }

    @Scheduled(cron = "${navix.extension.expiry-cron:0 5 0 * * *}", zone = "Asia/Kolkata")
    public void sweep() {
        locks.runExclusive(LOCK, Duration.ZERO, Duration.ofMinutes(10), () -> {
            expireAll();
            expireEmiOffers();
            int overdue = schedules.markOverdue(clock.instant());
            if (overdue > 0) {
                log.info("Instalments overdue: {}", overdue);
            }
        });
    }

    /** @return how many offers expired */
    public int expireAll() {
        int expired = 0;
        for (UUID id : extensions.expirable()) {
            try {
                if (extensions.expire(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Extension expiry skipped {}: {}", id, e.getMessage());
            }
        }
        if (expired > 0) {
            log.info("Extension expiry: {} offer(s) expired", expired);
        }
        return expired;
    }

    /** @return how many EMI offers expired */
    public int expireEmiOffers() {
        int expired = 0;
        for (UUID id : emis.expirable()) {
            try {
                if (emis.expire(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("EMI offer expiry skipped {}: {}", id, e.getMessage());
            }
        }
        return expired;
    }
}
