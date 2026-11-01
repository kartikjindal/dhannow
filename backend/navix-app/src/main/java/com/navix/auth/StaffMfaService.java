package com.navix.auth;

import com.navix.common.brand.BrandProperties;
import com.navix.common.exception.BusinessException;
import com.navix.config.MfaProperties;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff TOTP (PLAN Phase 7): RFC 6238, 30-second steps, 6 digits, one step of clock skew either way. The secret is
 * AES-GCM encrypted with {@code navix.mfa.encryption-key} ({@code NAVIX_MFA_ENCRYPTION_KEY}, base64 of 32 bytes)
 * before it reaches {@code staff_totp_secret}; a used step is remembered so a code cannot be replayed.
 *
 * <p>Required at sign-in for {@code navix.mfa.required-roles} (ADMIN, FINANCE, CREDIT_HEAD, DISBURSEMENT_HEAD) while
 * {@code navix.mfa.enforce} is on (the default; local runs may turn it off, prod refuses to boot with it off). Anyone
 * who has enrolled is asked for a code whatever their role.
 */
@Service
public class StaffMfaService {

    /** What a staff member scans: the base32 secret and the {@code otpauth://} URI for an authenticator app. */
    public record Enrolment(String secret, String otpauthUri) {
    }

    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    static final int WINDOW = 1;
    private static final int SECRET_BYTES = 20;
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final MfaProperties props;
    private final BrandProperties brand;
    private final Clock clock;

    public StaffMfaService(JdbcTemplate jdbc, MfaProperties props, BrandProperties brand, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.brand = brand;
        this.clock = clock;
    }

    /**
     * The sign-in gate, after the password matched. No code needed when MFA is not required and not enrolled.
     *
     * @throws BusinessException MFA_ENROLMENT_REQUIRED (a required role with no confirmed secret), MFA_REQUIRED
     *                           (no code sent) or INVALID_MFA_CODE
     */
    @Transactional
    public void checkAtLogin(Long staffId, String role, String code) {
        Map<String, Object> row = row(staffId);
        boolean enrolled = row != null && row.get("confirmed_at") != null;
        if (!enrolled) {
            if (props.enforce() && props.requiredRoles().contains(role)) {
                throw new BusinessException("MFA_ENROLMENT_REQUIRED",
                        "Set up an authenticator app for your account before you sign in.");
            }
            return;
        }
        if (code == null || code.isBlank()) {
            throw new BusinessException("MFA_REQUIRED", "Enter the 6-digit code from your authenticator app.");
        }
        acceptCode(staffId, row, code);
    }

    /** Start (or restart, while unconfirmed) enrolment: a fresh secret, shown once. */
    @Transactional
    public Enrolment enrol(Long staffId, String account) {
        Map<String, Object> row = row(staffId);
        if (row != null && row.get("confirmed_at") != null) {
            throw new BusinessException("MFA_ALREADY_ENROLLED",
                    "Your authenticator is already set up. Ask an administrator to reset it.");
        }
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        jdbc.update("insert into staff_totp_secret (staff_id, secret_enc, created_at) values (?, ?, ?)"
                        + " on conflict (staff_id) do update set secret_enc = excluded.secret_enc, last_step = null,"
                        + " created_at = excluded.created_at", staffId, encrypt(secret),
                Timestamp.from(clock.instant()));
        String b32 = base32(secret);
        String issuer = enc(String.valueOf(brand.name()));
        return new Enrolment(b32, "otpauth://totp/" + issuer + ":" + enc(account) + "?secret=" + b32 + "&issuer="
                + issuer + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS);
    }

    /** The first code from the app confirms the enrolment. */
    @Transactional
    public void confirm(Long staffId, String code) {
        Map<String, Object> row = row(staffId);
        if (row == null) {
            throw new BusinessException("MFA_NOT_ENROLLED", "Start setting up your authenticator first.");
        }
        if (row.get("confirmed_at") != null) {
            throw new BusinessException("MFA_ALREADY_ENROLLED", "Your authenticator is already set up.");
        }
        acceptCode(staffId, row, code);
        jdbc.update("update staff_totp_secret set confirmed_at = ? where staff_id = ?",
                Timestamp.from(clock.instant()), staffId);
    }

    /** An administrator clears a lost authenticator; the staff member enrols again at their next sign-in. */
    @Transactional
    public boolean reset(Long staffId) {
        return jdbc.update("delete from staff_totp_secret where staff_id = ?", staffId) > 0;
    }

    private void acceptCode(Long staffId, Map<String, Object> row, String code) {
        byte[] secret = decrypt((String) row.get("secret_enc"));
        Long last = row.get("last_step") == null ? null : ((Number) row.get("last_step")).longValue();
        long step = matchingStep(secret, code, clock.millis() / 1000 / STEP_SECONDS);
        if (step < 0 || (last != null && step <= last)) {
            throw new BusinessException("INVALID_MFA_CODE", "That code is not right. Use the latest code from your app.");
        }
        jdbc.update("update staff_totp_secret set last_step = ? where staff_id = ?", step, staffId);
    }

    private Map<String, Object> row(Long staffId) {
        return jdbc.queryForList("select secret_enc, confirmed_at, last_step from staff_totp_secret where staff_id = ?",
                staffId).stream().findFirst().orElse(null);
    }

    // ---- TOTP (RFC 6238 / RFC 4226) ----

    /** The step within ±{@link #WINDOW} of {@code now} whose code is {@code code}, or -1. */
    static long matchingStep(byte[] secret, String code, long now) {
        if (code == null || !code.trim().matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        for (long s = now - WINDOW; s <= now + WINDOW; s++) {
            if (code(secret, s).equals(code.trim())) {
                return s;
            }
        }
        return -1;
    }

    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] data) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(alphabet.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(alphabet.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    // ---- encryption at rest (AES-256-GCM, random 96-bit IV, stored as base64(iv || ciphertext)) ----

    String encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain);
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP secret encryption failed", e);
        }
    }

    byte[] decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, IV_BYTES));
            return c.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("TOTP secret could not be decrypted (wrong NAVIX_MFA_ENCRYPTION_KEY?)", e);
        }
    }

    private SecretKeySpec key() {
        byte[] k = props.keyBytes();
        if (k == null) {
            throw new BusinessException("MFA_NOT_CONFIGURED", "Two-step sign-in is not set up on this server.");
        }
        return new SecretKeySpec(k, "AES");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
