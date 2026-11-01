package com.navix.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What {@code GET /api/app/config} tells the app (PLAN Phase 8, CC-52): the oldest version allowed to run, the
 * newest one, a maintenance switch, which feature flags it may read, and the legal pages it opens (Phase 10a W1;
 * {@code version} stays null until the pages carry one, W2). {@code navix.mobile.*}; the defaults live here so the
 * lists are written once.
 */
@ConfigurationProperties(prefix = "navix.mobile")
public record MobileAppProperties(String minVersion, String latestVersion, boolean maintenance, String siteBaseUrl,
                                  List<String> flags, List<LegalDocument> legal) {

    public record LegalDocument(String key, String title, String path, String version) {
    }

    static final List<String> DEFAULT_FLAGS = List.of("loan-extension", "emi-conversion", "payment-gateway",
            "play-integrity", "push-fcm", "digio-mandate", "onboarding-paused");

    /** Which site page each app screen opens (PLAN Phase 10a W1). */
    static final List<LegalDocument> DEFAULT_LEGAL = List.of(
            new LegalDocument("terms", "Terms and conditions", "/terms.html", null),
            new LegalDocument("consent", "Consent", "/consent.html", null),
            new LegalDocument("privacy", "Privacy policy", "/privacy.html", null),
            new LegalDocument("fair-practices", "Fair practices code", "/fair-practices.html", null),
            new LegalDocument("faq", "Questions and answers", "/faq.html", null),
            new LegalDocument("grievance", "Grievance redressal", "/grievance.html", null),
            new LegalDocument("contact", "Contact us", "/contact.html", null),
            new LegalDocument("cookie-policy", "Cookie policy", "/cookie-policy.html", null),
            new LegalDocument("repayment-policy", "Repayment policy", "/repayment-policy.html", null),
            new LegalDocument("payment-refund-policy", "Payment and refund policy", "/payment-refund-policy.html", null),
            new LegalDocument("kfs-policy", "Key fact statement policy", "/kfs-policy.html", null));

    public MobileAppProperties {
        minVersion = minVersion == null || minVersion.isBlank() ? "1.0.0" : minVersion;
        latestVersion = latestVersion == null || latestVersion.isBlank() ? minVersion : latestVersion;
        siteBaseUrl = siteBaseUrl == null || siteBaseUrl.isBlank() ? "https://dhannow.com" : siteBaseUrl;
        flags = flags == null || flags.isEmpty() ? DEFAULT_FLAGS : flags;
        legal = legal == null || legal.isEmpty() ? DEFAULT_LEGAL : legal;
    }
}
