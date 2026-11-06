package com.navix.common.util;

import java.util.Set;

/**
 * Links in borrower SMS and email (PLAN Phase 10a). There is no borrower web in v1, so a link opens the app through
 * the site's {@code /app/open} page: {@code <base>/app/open?to=repay|loans|support}. Any other target goes to the
 * app's home ({@code <base>/app/open}), where the app routes from the journey.
 */
public final class AppLinks {

    /** The only screens a link may name; the site's {@code /app/open} page knows these three. */
    public static final Set<String> TARGETS = Set.of("repay", "loans", "support");

    private AppLinks() {}

    /** {@code https://dhannow.com/app/open?to=repay}, or the app home for {@code null} or any other target. */
    public static String open(String baseUrl, String target) {
        String base = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "") + "/app/open";
        return target != null && TARGETS.contains(target) ? base + "?to=" + target : base;
    }
}
