package com.navix.config;

import com.navix.common.brand.LenderProperties;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.sms.SmsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Refuses to boot when {@code NAVIX_ENV=prod} is paired with a demo-only setting.
 *
 * <p>The demo features themselves stay — they are documented and load-bearing for the demo
 * (CLAUDE.md §4.4). What is missing is a tripwire: each one fails OPEN, silently, and the failure
 * looks exactly like a working system.
 *
 * <ul>
 *   <li>the built-in {@code navix.auth.secret} — it is committed, so anyone with the repo can forge
 *       a token for any customer or staff member;</li>
 *   <li>{@code navix.sms.mock} — the fixed code verifies for ANY mobile with no stored OTP, which is
 *       a total authentication bypass, not merely a convenience;</li>
 *   <li>{@code navix.sms.dev-echo} — returns the OTP in the HTTP response, same class of bypass.</li>
 *   <li>{@code navix.esign.provider=mock} — reports every loan agreement as signed without anyone
 *       signing anything, so the file reaches disbursement on a signature that does not exist.</li>
 *   <li>staff MFA switched off ({@code navix.mfa.enforce=false}) or no valid {@code navix.mfa.encryption-key};</li>
 *   <li>a blank or {@code [placeholder]} {@code navix.lender.*} value — every loan document names the
 *       lender of record, and a document naming no real lender must never reach a borrower.</li>
 *   <li>PLAN Phase 8: {@code navix.integrity.allow-android-without-check=true} (any client could skip the challenge
 *       by sending {@code X-Client: android}), and the {@code push-fcm} / {@code play-integrity} flags ON with no
 *       Google service account (every push would fail; every app sign-in would be refused).</li>
 * </ul>
 *
 * <p>Thrown from {@code @PostConstruct}, so it fires during bean initialisation — before the web
 * server accepts a single request.
 *
 * <p>Lives in {@code navix-app} rather than {@code JwtService}: that class is in {@code navix-common}
 * and is constructed directly by unit tests (no {@code @PostConstruct} would run), and it knows
 * nothing about the SMS properties. One validator covering both is the smaller, more findable diff.
 */
@Component
@RequiredArgsConstructor
public class ProductionConfigGuard {

    /** Must stay identical to the {@code @Value} default below and to JwtService's. */
    private static final String DEFAULT_SECRET = "navix-local-dev-secret-change-me";

    /** Below this, the secret is guessable regardless of the SHA-256 that widens it to 256 bits. */
    private static final int MIN_SECRET_LENGTH = 32;

    private final SmsProperties sms;
    private final FeatureFlagService featureFlags;
    private final LenderProperties lender;
    private final MfaProperties mfa;

    @Value("${NAVIX_ENV:dev}")
    private String env;

    // The literal is shared with the constant above (a compile-time constant expression), so the
    // check can never drift from the default it is checking for.
    @Value("${navix.auth.secret:" + DEFAULT_SECRET + "}")
    private String secret;

    @Value("${navix.esign.provider:signzy}")
    private String esignProvider;

    @Value("${navix.digio.provider:digio}")
    private String digioProvider;

    @Value("${navix.integrity.allow-android-without-check:false}")
    private boolean androidWithoutIntegrity;

    @Value("${navix.google.service-account-path:}")
    private String googleKeyPath = "";

    @PostConstruct
    void check() {
        if (!"prod".equalsIgnoreCase(env)) {
            return;
        }
        if (DEFAULT_SECRET.equals(secret) || secret == null || secret.length() < MIN_SECRET_LENGTH) {
            // Length matters independently of the equality check: the key is a single unsalted
            // SHA-256 of this string, so AUTH_SECRET=changeme yields a "valid" 256-bit key with
            // eight characters of entropy behind it.
            throw new IllegalStateException("NAVIX_ENV=prod with the built-in or too-short "
                    + "navix.auth.secret — set AUTH_SECRET to at least " + MIN_SECRET_LENGTH
                    + " random characters (openssl rand -base64 48).");
        }
        if (sms.mock()) {
            throw new IllegalStateException("NAVIX_ENV=prod with navix.sms.mock=true — the fixed mock "
                    + "OTP verifies for ANY mobile. Unset NAVIX_SMS_MOCK.");
        }
        if (sms.devEcho()) {
            throw new IllegalStateException("NAVIX_ENV=prod with navix.sms.dev-echo=true — the OTP is "
                    + "returned in the HTTP response. Unset NAVIX_SMS_DEV_ECHO.");
        }
        if ("mock".equalsIgnoreCase(esignProvider)) {
            // Worse than a bypass: the borrower's file reaches disbursement carrying a PASS on the one
            // check that is a legal act, with no signature behind it and nothing to enforce later.
            throw new IllegalStateException("NAVIX_ENV=prod with navix.esign.provider=mock — every loan "
                    + "agreement would be recorded as signed without a real Aadhaar e-signature. "
                    + "Unset NAVIX_ESIGN_PROVIDER.");
        }
        if (!mfa.enforce()) {
            throw new IllegalStateException("NAVIX_ENV=prod with navix.mfa.enforce=false — ADMIN, FINANCE and the "
                    + "Heads would sign in with a password alone. Unset NAVIX_STAFF_MFA_ENFORCE.");
        }
        if (mfa.keyBytes() == null) {
            throw new IllegalStateException("NAVIX_ENV=prod without a valid navix.mfa.encryption-key — set "
                    + "NAVIX_MFA_ENCRYPTION_KEY to base64 of 32 random bytes (openssl rand -base64 32).");
        }
        if (androidWithoutIntegrity) {
            throw new IllegalStateException("NAVIX_ENV=prod with navix.integrity.allow-android-without-check=true — "
                    + "any client could skip the sign-in challenge by sending X-Client: android. Unset "
                    + "NAVIX_INTEGRITY_ALLOW_ANDROID_WITHOUT_CHECK.");
        }
        if (!lender.unset().isEmpty()) {
            throw new IllegalStateException("NAVIX_ENV=prod with the lender of record unset or a placeholder: "
                    + String.join(", ", lender.unset()) + " — set the NAVIX_LENDER_* values.");
        }
    }

    /**
     * Digio is checked <b>only when the {@code digio-mandate} flag is actually on</b>, and after the
     * context is up so the DB-backed flag can be read.
     *
     * <p>The eSign lesson above is about SILENT degradation: the mock reported agreements as signed while
     * the journey ran. With {@code digio-mandate} off the mandate step does not exist, nothing calls the
     * adapter, and nothing degrades — so an unconditional check only refused every unrelated prod deploy
     * until five {@code NAVIX_DIGIO_*} values existed in SSM for a disabled feature. With the flag on the
     * mock is exactly as dangerous as the eSign one (mandates recorded as registered, nothing filed at
     * NPCI), and this still fails the boot.
     */
    /** As {@link #checkDigio}: only a flag that is actually ON needs the Google key behind it. */
    @EventListener(ApplicationReadyEvent.class)
    void checkGoogle() {
        if (!"prod".equalsIgnoreCase(env) || (googleKeyPath != null && !googleKeyPath.isBlank())) {
            return;
        }
        for (String flag : java.util.List.of("push-fcm", "play-integrity")) {
            if (featureFlags.isEnabled(flag, false)) {
                throw new IllegalStateException("NAVIX_ENV=prod with the " + flag + " flag ON and no "
                        + "navix.google.service-account-path — set NAVIX_GOOGLE_SERVICE_ACCOUNT_PATH, or turn the flag off.");
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void checkDigio() {
        if (!"prod".equalsIgnoreCase(env) || !"mock".equalsIgnoreCase(digioProvider)) {
            return;
        }
        if (featureFlags.isEnabled("digio-mandate", false)) {
            throw new IllegalStateException("NAVIX_ENV=prod with navix.digio.provider=mock while the "
                    + "digio-mandate flag is ON — every eMandate would be recorded as registered without a "
                    + "real NPCI filing. Set NAVIX_DIGIO_PROVIDER=digio (and the NAVIX_DIGIO_* "
                    + "credentials), or turn the flag off.");
        }
    }
}
