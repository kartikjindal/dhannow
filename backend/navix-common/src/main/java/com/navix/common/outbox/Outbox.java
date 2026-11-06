package com.navix.common.outbox;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A small transactional outbox (PLAN Phase 9, EXT-22): side effects that must follow a commit (the schedule PDF,
 * the closure receipt, the extension addendum and their notifications) are written as an {@code outbox} row in
 * the same transaction as the state change, tried once right after the commit, and retried by
 * {@code OutboxPoller} with backoff (1, 5, 30 minutes, then every 2 hours) until they succeed. The third failure
 * of a row logs {@code OUTBOX_ALERT} at ERROR for the alarm.
 *
 * <p>ponytail: one table, no partitioning or dead-letter state; a row that keeps failing keeps retrying every two
 * hours and alerting. Add a give-up status if that ever gets noisy.
 */
@Component
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);
    static final int ALERT_AFTER = 3;
    private static final int[] BACKOFF_MINUTES = {1, 5, 30, 120};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate newTx;
    private final ObjectProvider<OutboxHandler> handlers;
    private volatile Map<String, OutboxHandler> byKind;

    public Outbox(JdbcTemplate jdbc, PlatformTransactionManager txManager, ObjectProvider<OutboxHandler> handlers) {
        this.jdbc = jdbc;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.handlers = handlers;
    }

    /** Queue {@code kind} in the caller's transaction and try it right after the commit, on this thread. */
    public void enqueue(String kind, String payloadJson) {
        enqueue(kind, payloadJson, Runnable::run);
    }

    /** As {@link #enqueue(String, String)}, with the first try on {@code executor} (notifications stay off the request). */
    public void enqueue(String kind, String payloadJson, Executor executor) {
        // The poller leaves a fresh row alone for a minute, so it never races the first try.
        Long id = jdbc.queryForObject("insert into outbox (kind, payload, next_attempt_at)"
                + " values (?, cast(? as jsonb), now() + interval '1 minute') returning id", Long.class, kind, payloadJson);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(() -> run(id));
                }
            });
        } else {
            executor.execute(() -> run(id));
        }
    }

    /** Run the rows that are due. @return how many ran (successfully or not). */
    public int runDue(int limit) {
        List<Long> due = jdbc.queryForList("select id from outbox where done_at is null and next_attempt_at <= now()"
                + " order by next_attempt_at, id limit ?", Long.class, limit);
        due.forEach(this::run);
        return due.size();
    }

    /**
     * One try of row {@code id}. The row is locked for the try ({@code skip locked}: a row another instance or thread
     * is running is left alone), the handler runs in its own transaction, then the row is marked done or rescheduled.
     */
    public void run(long id) {
        newTx.executeWithoutResult(outer -> {
            List<String[]> rows = jdbc.query("select kind, cast(payload as text), attempts from outbox"
                    + " where id = ? and done_at is null for update skip locked",
                    (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, id);
            if (rows.isEmpty()) {
                return;
            }
            String kind = rows.get(0)[0];
            int attempts = Integer.parseInt(rows.get(0)[2]) + 1;
            try {
                OutboxHandler h = handlers().get(kind);
                if (h == null) {
                    throw new IllegalStateException("No outbox handler for " + kind);
                }
                newTx.executeWithoutResult(inner -> h.handle(kind, rows.get(0)[1]));
                jdbc.update("update outbox set done_at = now(), attempts = ?, last_error = null where id = ?", attempts, id);
            } catch (RuntimeException e) {
                jdbc.update("update outbox set attempts = ?, last_error = ?, next_attempt_at = now()"
                        + " + make_interval(mins => ?) where id = ?", attempts, truncate(e.toString()),
                        backoffMinutes(attempts), id);
                if (attempts >= ALERT_AFTER) {
                    log.error("OUTBOX_ALERT kind={} id={} attempts={} error={}", kind, id, attempts, e.toString());
                } else {
                    log.warn("outbox kind={} id={} attempt {} failed, will retry: {}", kind, id, attempts, e.toString());
                }
            }
        });
    }

    static int backoffMinutes(int attempt) {
        return BACKOFF_MINUTES[Math.min(attempt, BACKOFF_MINUTES.length) - 1];
    }

    private Map<String, OutboxHandler> handlers() {
        Map<String, OutboxHandler> m = byKind;
        if (m == null) {
            m = new java.util.HashMap<>();
            for (OutboxHandler h : handlers.orderedStream().toList()) {
                for (String k : h.kinds()) {
                    m.put(k, h);
                }
            }
            byKind = m;
        }
        return m;
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
