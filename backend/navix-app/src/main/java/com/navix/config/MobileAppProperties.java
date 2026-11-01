package com.navix.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What {@code GET /api/app/config} tells the app (PLAN Phase 8, CC-52): the oldest version allowed to run, the
 * newest one, a maintenance switch, which feature flags it may read, and the legal pages it opens (Phase 10a W1)
 * with each page's version from {@code site/doc-versions.json} (W2). {@code navix.mobile.*}; the defaults live here so the
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
            new LegalDocument("terms", "Terms and conditions", "/terms.html", "1"),
            new LegalDocument("consent", "Consent", "/consent.html", "1"),
            new LegalDocument("privacy", "Privacy policy", "/privacy.html", "1"),
            new LegalDocument("fair-practices", "Fair practices code", "/fair-practices.html", "1"),
            new LegalDocument("faq", "Questions and answers", "/faq.html", "1"),
            new LegalDocument("grievance", "Grievance redressal", "/grievance.html", "1"),
            new LegalDocument("contact", "Contact us", "/contact.html", "1"),
            new LegalDocument("cookie-policy", "Cookie policy", "/cookie-policy.html", "1"),
            new LegalDocument("repayment-policy", "Repayment policy", "/repayment-policy.html", "1"),
            new LegalDocument("payment-refund-policy", "Payment and refund policy", "/payment-refund-policy.html", "1"),
            new LegalDocument("kfs-policy", "Key fact statement policy", "/kfs-policy.html", "1"));

    public MobileAppProperties {
        minVersion = minVersion == null || minVersion.isBlank() ? "1.0.0" : minVersion;
        latestVersion = latestVersion == null || latestVersion.isBlank() ? minVersion : latestVersion;
        siteBaseUrl = siteBaseUrl == null || siteBaseUrl.isBlank() ? "https://dhannow.com" : siteBaseUrl;
        flags = flags == null || flags.isEmpty() ? DEFAULT_FLAGS : flags;
        legal = legal == null || legal.isEmpty() ? DEFAULT_LEGAL : legal;
    }
}
