package com.navix.common.pii;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Field encryption for Aadhaar, PAN and bank account numbers (PLAN Phase 9). AES-256-GCM under one data key,
 * the envelope's inner half: the data key itself is unwrapped once at boot by a {@code DataKeySource} (AWS KMS in
 * the cloud, a configured value locally). Stored form {@code pii1:<base64(iv || ciphertext || tag)>}; anything
 * without the prefix is read back as it is, so rows written before encryption still load (migration window).
 *
 * <p>{@link #index} is the blind index for equality lookups (PAN dedupe, the Aadhaar duplicate rule, search by a
 * full PAN): HMAC-SHA256 under a key derived from the data key, over the normalised value. Same value, same index;
 * nothing about the value can be read from it without the key.
 *
 * <p>One instance per process, installed at boot ({@link #install}) so JPA converters and entity callbacks, which
 * Hibernate creates itself, can reach it.
 */
public final class PiiCipher {

    public static final String PREFIX = "pii1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile PiiCipher current;

    private final SecretKeySpec aesKey;
    private final SecretKeySpec indexKey;

    /** @param dataKey 32 bytes: the unwrapped data key. */
    public PiiCipher(byte[] dataKey) {
        if (dataKey == null || dataKey.length != 32) {
            throw new IllegalArgumentException("The PII data key must be 32 bytes");
        }
        this.aesKey = new SecretKeySpec(dataKey, "AES");
        this.indexKey = new SecretKeySpec(hmac(new SecretKeySpec(dataKey, "HmacSHA256"),
                "dhannow-pii-blind-index-v1"), "HmacSHA256");
    }

    /** Called once at boot by the key configuration. */
    public static void install(PiiCipher cipher) {
        current = cipher;
    }

    public static PiiCipher get() {
        PiiCipher c = current;
        if (c == null) {
            throw new IllegalStateException("The PII key is not loaded (navix.pii.*)");
        }
        return c;
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public String encrypt(String plain) {
        if (plain == null || isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length)
                    .put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PII encryption failed", e);
        }
    }

    /** The plain value; a value stored before encryption comes back unchanged. A tampered or foreign-key value throws. */
    public String decrypt(String stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("PII value cannot be decrypted with the loaded key", e);
        }
    }

    /** Hex blind index of an already-normalised value, or null for a blank one. */
    public String index(String normalised) {
        if (normalised == null || normalised.isEmpty()) {
            return null;
        }
        return HexFormat.of().formatHex(hmac(indexKey, normalised));
    }

    // ---- the normalisations every lookup and every write share ----

    /** PAN: trimmed, upper case. */
    public static String panIndex(String pan) {
        return pan == null ? null : get().index(pan.trim().toUpperCase(Locale.ROOT));
    }

    /** Aadhaar (and any number): digits only. */
    public static String digitsIndex(String value) {
        return value == null ? null : get().index(value.replaceAll("\\D", ""));
    }

    private static byte[] hmac(SecretKeySpec key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
