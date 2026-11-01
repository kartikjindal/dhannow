package com.navix.common.idempotency;

import com.navix.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Run a state-changing action at most once per client key (CC-22, {@code idempotency_key}, V101).
 * Used by repayment submit now, and by extension/EMI activation and gateway webhooks later.
 *
 * <p>The key row is inserted in the caller's transaction before the action runs, so the action and its
 * key commit or roll back together. A concurrent request with the same key waits on the primary key until
 * the first commits, then finds its result; if the first rolled back, the retry simply runs.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final JdbcTemplate jdbc;

    /**
     * @param scope   what kind of action (a key is only ever valid for one)
     * @param key     the client's key, one per user action
     * @param request everything that identifies the request (actor, target, body); a replay must match it
     * @param action  runs once and returns the id of what it created
     * @return the id from the first run
     */
    @Transactional
    public Long execute(String scope, String key, String request, Supplier<Long> action) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException("IDEMPOTENCY_KEY_INVALID", "Send one Idempotency-Key (up to 128 characters) per action.");
        }
        String hash = sha256(scope + "\n" + request);
        int inserted = jdbc.update("insert into idempotency_key (key, scope, request_hash) values (?, ?, ?)"
                + " on conflict (key) do nothing", key, scope, hash);
        if (inserted == 0) {
            var row = jdbc.queryForMap("select scope, request_hash, result_id from idempotency_key where key = ?", key);
            if (!scope.equals(row.get("scope")) || !hash.equals(row.get("request_hash"))) {
                throw new BusinessException("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different request.");
            }
            return ((Number) row.get("result_id")).longValue();
        }
        Long result = action.get();
        jdbc.update("update idempotency_key set result_id = ? where key = ?", result, key);
        return result;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
