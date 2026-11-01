package com.navix.scheduler;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A ShedLock-style lease on a {@code scheduler_lock} row (V98, PLAN Phase 3): of several app instances
 * firing the same job, only the one that takes the lease runs it. Times are the database's, so app
 * clocks (or a test's fixed {@code Clock}) do not matter.
 *
 * <p>{@code atMost} bounds a crashed holder; {@code atLeast} keeps the lease after a quick run, so an
 * instance whose cron fires a few seconds late does not run a daily job twice.
 */
@Component
public class SchedulerLock {

    private final JdbcTemplate jdbc;
    private final String owner = hostName() + "/" + UUID.randomUUID();

    public SchedulerLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Run {@code task} if this instance takes the lease on {@code name}. @return whether it ran. */
    public boolean runExclusive(String name, Duration atLeast, Duration atMost, Runnable task) {
        // One statement: a missing row is inserted, an expired one taken over, a live one left alone.
        int taken = jdbc.update("""
                insert into scheduler_lock (name, lock_until, locked_at, locked_by)
                values (?, now() + make_interval(secs => ?), now(), ?)
                on conflict (name) do update
                    set lock_until = excluded.lock_until, locked_at = excluded.locked_at, locked_by = excluded.locked_by
                    where scheduler_lock.lock_until <= now()
                """, name, (double) atMost.toSeconds(), owner);
        if (taken == 0) {
            return false;
        }
        try {
            task.run();
        } finally {
            jdbc.update("update scheduler_lock set lock_until = greatest(now(), locked_at + make_interval(secs => ?))"
                    + " where name = ? and locked_by = ?", (double) atLeast.toSeconds(), name, owner);
        }
        return true;
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException e) {
            return "unknown";
        }
    }
}
