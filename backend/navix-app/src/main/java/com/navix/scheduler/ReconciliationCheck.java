package com.navix.scheduler;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly money invariant (PLAN Phase 10b revenue recognition): every disbursed loan on the DhanNow terms
 * satisfies {@code principal = net disbursed + fee + GST + upfront interest}. A break logs
 * {@code RECONCILIATION_MISMATCH} at ERROR (a CloudWatch metric filter alerts on it) with the loan ids.
 */
@Component
public class ReconciliationCheck {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationCheck.class);

    static final String LOCK = "reconciliation-check";

    /** Loans whose upfront deductions do not add back up to the principal. */
    static final String MISMATCHES = "select l.id from loan l where l.upfront_interest_paise is not null"
            + " and l.principal <> coalesce(l.net_disbursed, 0) + coalesce(l.processing_fee, 0) + coalesce(l.gst, 0)"
            + " + l.upfront_interest_paise order by l.id limit 100";

    private final NamedParameterJdbcTemplate jdbc;
    private final SchedulerLock locks;

    public ReconciliationCheck(NamedParameterJdbcTemplate jdbc, SchedulerLock locks) {
        this.jdbc = jdbc;
        this.locks = locks;
    }

    @Scheduled(cron = "${navix.reconciliation.cron:0 15 2 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        locks.runExclusive(LOCK, Duration.ZERO, Duration.ofMinutes(10), this::check);
    }

    /** The loan ids that break the invariant (at most 100); empty when the book reconciles. */
    public List<Long> check() {
        List<Long> bad = jdbc.queryForList(MISMATCHES, new MapSqlParameterSource(), Long.class);
        if (!bad.isEmpty()) {
            log.error("RECONCILIATION_MISMATCH principal != net + fee + GST + interest on loans {}", bad);
        }
        return bad;
    }
}
