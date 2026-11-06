package com.navix.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Staff MFA ({@code navix.mfa.*}, PLAN Phase 7). {@code enforce} (NAVIX_STAFF_MFA_ENFORCE, default on) makes a TOTP
 * code mandatory at sign-in for {@code requiredRoles}; {@code encryptionKey} (NAVIX_MFA_ENCRYPTION_KEY) is base64 of
 * 32 random bytes ({@code openssl rand -base64 32}) and encrypts the stored secrets. Never committed.
 */
@ConfigurationProperties(prefix = "navix.mfa")
public record MfaProperties(@DefaultValue("true") boolean enforce, String encryptionKey,
                            @DefaultValue({"ADMIN", "FINANCE", "CREDIT_HEAD", "DISBURSEMENT_HEAD"})
                            List<String> requiredRoles) {

    /** The configured key, or null when blank or not base64 of exactly 32 bytes. */
    public byte[] keyBytes() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            return null;
        }
        try {
            byte[] k = java.util.Base64.getDecoder().decode(encryptionKey.trim());
            return k.length == 32 ? k : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
