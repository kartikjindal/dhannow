package com.navix.auth;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link AuthStateStore} on Postgres ({@code otp_challenge}, {@code attempt_counter}, V109).
 *
 * <p>Every operation is a row-locked read-modify-write in its <b>own</b> transaction ({@code REQUIRES_NEW}). The
 * callers often run inside a service transaction that then throws ({@code INVALID_OTP}, {@code TOO_MANY_ATTEMPTS});
 * joining it would roll the strike back with the rejection it caused, and the limiter would never fire.
 *
 * <p>Keys, subjects and codes are stored as HMAC-SHA256 under {@code navix.auth.secret}, so the tables hold no
 * mobile number, email address or OTP.
 */
@Component
public class JdbcAuthStateStore implements AuthStateStore {

    private static final Timestamp EPOCH = Timestamp.from(Instant.EPOCH);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SecretKeySpec key;

    public JdbcAuthStateStore(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                              @Value("${navix.auth.secret:navix-local-dev-secret-change-me}") String secret) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public Counter updateCounter(String rawKey, UnaryOperator<Counter> fn) {
        String k = hmac("counter:" + rawKey);
        return tx.execute(s -> {
            // A placeholder row with an expired window reads exactly like "no row", and gives the select below
            // something to lock, so two first hits on the same key cannot both insert.
            jdbc.update("insert into attempt_counter (key, window_start, count) values (?, ?, 0) on conflict do nothing",
                    k, EPOCH);
            Counter current = jdbc.queryForObject(
                    "select count, window_start, blocked_until from attempt_counter where key = ? for update",
                    (rs, i) -> new Counter(rs.getInt(1), rs.getTimestamp(2).toInstant(),
                            rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()), k);
            Counter next = fn.apply(current);
            jdbc.update("update attempt_counter set count = ?, window_start = ?, blocked_until = ? where key = ?",
                    next.count(), Timestamp.from(next.windowStart()),
                    next.blockUntil() == null ? null : Timestamp.from(next.blockUntil()), k);
            return next;
        });
    }

    @Override
    public void clearCounter(String rawKey) {
        String k = hmac("counter:" + rawKey);
        tx.executeWithoutResult(s -> jdbc.update("delete from attempt_counter where key = ?", k));
    }

    @Override
    public void putChallenge(String purpose, String subject, String code, Instant expiresAt) {
        String subjectHash = hmac("subject:" + subject);
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    insert into otp_challenge (purpose, subject_hash, code_hash, expires_at, attempts)
                    values (?, ?, ?, ?, 0)
                    on conflict (purpose, subject_hash)
                    do update set code_hash = excluded.code_hash, expires_at = excluded.expires_at, attempts = 0,
                                  created_at = now()
                    """, purpose, subjectHash, hmac("code:" + subjectHash + ":" + code), Timestamp.from(expiresAt));
            // ponytail: housekeeping rides on OTP sends (a few per sign-in) instead of a scheduled job. The longest
            // window any caller uses is an hour, so a day of history is never read again.
            jdbc.update("delete from otp_challenge where expires_at < now() - interval '1 day'");
            jdbc.update("delete from attempt_counter where window_start < now() - interval '1 day'"
                    + " and (blocked_until is null or blocked_until < now())");
        });
    }

    @Override
    public boolean consumeChallenge(String purpose, String subject, String code, int maxAttempts, Instant now) {
        if (code == null) {
            return false;
        }
        String subjectHash = hmac("subject:" + subject);
        String submitted = hmac("code:" + subjectHash + ":" + code);
        Boolean matched = tx.execute(s -> {
            List<Object[]> rows = jdbc.query("select id, code_hash, expires_at, attempts from otp_challenge"
                            + " where purpose = ? and subject_hash = ? for update",
                    (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getInt(4)},
                    purpose, subjectHash);
            if (rows.isEmpty()) {
                return false;
            }
            Object[] row = rows.get(0);
            long id = (Long) row[0];
            if (now.isAfter((Instant) row[2]) || (Integer) row[3] >= maxAttempts) {
                jdbc.update("delete from otp_challenge where id = ?", id); // expired or locked out
                return false;
            }
            if (MessageDigest.isEqual(((String) row[1]).getBytes(StandardCharsets.US_ASCII),
                    submitted.getBytes(StandardCharsets.US_ASCII))) {
                jdbc.update("delete from otp_challenge where id = ?", id); // single use
                return true;
            }
            jdbc.update("update otp_challenge set attempts = attempts + 1 where id = ?", id);
            return false;
        });
        return Boolean.TRUE.equals(matched);
    }

    @Override
    public void removeChallenge(String purpose, String subject) {
        String subjectHash = hmac("subject:" + subject);
        tx.executeWithoutResult(s -> jdbc.update(
                "delete from otp_challenge where purpose = ? and subject_hash = ?", purpose, subjectHash));
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
