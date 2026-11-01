package com.navix.config;

import com.navix.common.security.CurrentActor;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The PII_VIEW audit trail (PLAN Phase 9): one append-only {@code pii_access_log} row per staff request that was
 * shown an Aadhaar, PAN or account number in full. Which fields and which URL, never the values.
 */
@Component
public class PiiAccessLog {

    private static final Logger log = LoggerFactory.getLogger(PiiAccessLog.class);

    private final JdbcTemplate jdbc;

    public PiiAccessLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(CurrentActor actor, String method, String path, Set<String> fields) {
        try {
            jdbc.update("insert into pii_access_log (staff_id, role, method, path, fields) values (?, ?, ?, ?, ?)",
                    actor.id(), actor.role(), method, path == null ? "" : truncate(path, 500),
                    String.join(",", fields));
        } catch (RuntimeException e) {
            // The response has been written; losing the row must not turn it into an error.
            log.error("pii_access_log write failed staff={} fields={}: {}", actor.id(), fields, e.toString());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
