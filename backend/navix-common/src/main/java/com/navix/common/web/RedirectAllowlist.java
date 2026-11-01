package com.navix.common.web;

import com.navix.common.exception.BusinessException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where a provider (DigiLocker, e-sign) may send the borrower back to (PLAN Phase 8, CC-34). The client names the
 * URL, so without this anyone could have the consent or signing journey return to their own site carrying the
 * session. Only {@code https://dhannow.com/app/cb/...} is allowed (the app-link fallback pages, Phase 10a W3);
 * outside prod, {@code navix.app.redirect-allowlist-dev} adds the local web (default {@code http://localhost:3000/}).
 *
 * <p>Each entry is a URL prefix: scheme, host and port must match exactly and the path must start with the
 * entry's path. User info and {@code ..} segments are refused outright.
 */
@Component
public class RedirectAllowlist {

    private final List<URI> allowed;

    public RedirectAllowlist(@Value("${navix.app.redirect-allowlist:https://dhannow.com/app/cb/}") String allowlist,
                             @Value("${navix.app.redirect-allowlist-dev:http://localhost:3000/}") String devAllowlist,
                             @Value("${NAVIX_ENV:dev}") String env) {
        String all = "prod".equalsIgnoreCase(env) ? allowlist : allowlist + "," + devAllowlist;
        this.allowed = Arrays.stream(all.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(URI::create).toList();
    }

    /** Refuse {@code url} with {@code REDIRECT_NOT_ALLOWED} unless it sits under an allowlisted prefix. */
    public String require(String url) {
        if (!allowed(url)) {
            throw new BusinessException("REDIRECT_NOT_ALLOWED", "That return address is not allowed");
        }
        return url;
    }

    public boolean allowed(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        URI u;
        try {
            u = new URI(url.trim()).normalize();
        } catch (Exception e) {
            return false;
        }
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        if (u.getScheme() == null || u.getHost() == null || u.getRawUserInfo() != null || path.contains("..")
                || path.toLowerCase(Locale.ROOT).contains("%2e")) {
            return false;
        }
        for (URI a : allowed) {
            if (a.getScheme().equalsIgnoreCase(u.getScheme()) && a.getHost().equalsIgnoreCase(u.getHost())
                    && port(a) == port(u) && path.startsWith(a.getRawPath() == null ? "/" : a.getRawPath())) {
                return true;
            }
        }
        return false;
    }

    private static int port(URI u) {
        if (u.getPort() != -1) {
            return u.getPort();
        }
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : "http".equalsIgnoreCase(u.getScheme()) ? 80 : -1;
    }
}
