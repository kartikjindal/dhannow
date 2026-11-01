package com.navix.auth;

import com.navix.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * App refresh tokens (PLAN Phase 8, V108). One family per device sign-in; every refresh rotates the token and the
 * old one is spent. A spent token presented again means it was copied (CC-39): the whole family is revoked and the
 * device must sign in again. Only the SHA-256 of a token is stored.
 *
 * <p>Revocations commit in their own transaction before the caller throws, so a rejected refresh still burns
 * the family.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A new refresh token (shown once) and when it lapses. */
    public record Issued(String token, Instant expiresAt) {
    }

    /** A successful rotation: whose session it is and the token that replaces the presented one. */
    public record Rotated(long customerId, String deviceId, Issued next) {
    }

    private enum Outcome { OK, UNKNOWN, EXPIRED, REUSED, WRONG_DEVICE }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration ttl;

    public RefreshTokenService(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                               @Value("${navix.auth.refresh-ttl-days:30}") long ttlDays) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ttl = Duration.ofDays(ttlDays);
    }

    /** Start a new family for a fresh sign-in on {@code deviceId}. Earlier sessions on that device are ended. */
    public Issued issue(long customerId, String deviceId) {
        return tx.execute(s -> {
            jdbc.update("update refresh_token set revoked_at = now(), revoke_reason = 'LOGOUT'"
                    + " where customer_id = ? and device_id = ? and revoked_at is null", customerId, deviceId);
            return insert(customerId, deviceId, UUID.randomUUID());
        });
    }

    /**
     * Spend {@code token} for a new one. Refused with {@code REFRESH_INVALID} (unknown, expired, or sent from another
     * device, which also revokes the family) or {@code REFRESH_REUSED} (already spent: the family is revoked).
     */
    public Rotated rotate(String token, String deviceId) {
        Object[] result = new Object[2];
        Outcome outcome = tx.execute(s -> {
            List<Object[]> rows = jdbc.query("select id, customer_id, device_id, family_id, expires_at, revoked_at,"
                            + " revoke_reason from refresh_token where token_hash = ? for update",
                    (rs, i) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getString(3),
                            rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6),
                            rs.getString(7)},
                    hash(token));
            if (rows.isEmpty()) {
                return Outcome.UNKNOWN;
            }
            Object[] r = rows.get(0);
            long customerId = (Long) r[1];
            UUID family = (UUID) r[3];
            if (r[5] != null) {
                // Rotated away earlier and now back: someone holds a copy. Burn every token of this sign-in.
                if ("ROTATED".equals(r[6])) {
                    revokeFamily(family, "REUSED");
                    log.warn("refresh token reuse customerId={} family revoked", customerId);
                    return Outcome.REUSED;
                }
                return Outcome.UNKNOWN; // signed out
            }
            if (deviceId == null || !deviceId.equals(r[2])) {
                revokeFamily(family, "REUSED");
                log.warn("refresh token presented from another device customerId={} family revoked", customerId);
                return Outcome.WRONG_DEVICE;
            }
            if (Instant.now().isAfter((Instant) r[4])) {
                return Outcome.EXPIRED;
            }
            jdbc.update("update refresh_token set revoked_at = now(), revoke_reason = 'ROTATED' where id = ?", r[0]);
            result[0] = new Rotated(customerId, deviceId, insert(customerId, deviceId, family));
            return Outcome.OK;
        });
        if (outcome == Outcome.OK) {
            return (Rotated) result[0];
        }
        if (outcome == Outcome.REUSED) {
            throw new BusinessException("REFRESH_REUSED", "Your session ended for your safety. Please sign in again.");
        }
        throw new BusinessException("REFRESH_INVALID", "Your session has ended. Please sign in again.");
    }

    /**
     * Sign out the session {@code token} belongs to (its whole family). Returns its owner and device, or null when
     * the token is unknown. Idempotent.
     */
    public Rotated revoke(String token) {
        return tx.execute(s -> {
            List<Object[]> rows = jdbc.query("select customer_id, device_id, family_id from refresh_token"
                            + " where token_hash = ?",
                    (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class)}, hash(token));
            if (rows.isEmpty()) {
                return null;
            }
            revokeFamily((UUID) rows.get(0)[2], "LOGOUT");
            return new Rotated((Long) rows.get(0)[0], (String) rows.get(0)[1], null);
        });
    }

    /** End every session on one device (Settings > Devices > Sign out). */
    public void revokeDevice(long customerId, String deviceId, String reason) {
        tx.executeWithoutResult(s -> jdbc.update("update refresh_token set revoked_at = now(), revoke_reason = ?"
                + " where customer_id = ? and device_id = ? and revoked_at is null", reason, customerId, deviceId));
    }

    /** End every session of the customer (CC-38, "Log out of all devices"). */
    public void revokeAll(long customerId) {
        tx.executeWithoutResult(s -> jdbc.update("update refresh_token set revoked_at = now(),"
                + " revoke_reason = 'ALL_DEVICES' where customer_id = ? and revoked_at is null", customerId));
    }

    private void revokeFamily(UUID family, String reason) {
        jdbc.update("update refresh_token set revoked_at = now(), revoke_reason = ? where family_id = ?"
                + " and revoked_at is null", reason, family);
    }

    private Issued insert(long customerId, String deviceId, UUID family) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = Instant.now().plus(ttl);
        jdbc.update("insert into refresh_token (customer_id, device_id, family_id, token_hash, expires_at)"
                + " values (?, ?, ?, ?, ?)", customerId, deviceId, family, hash(token), Timestamp.from(expiresAt));
        return new Issued(token, expiresAt);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(token).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
