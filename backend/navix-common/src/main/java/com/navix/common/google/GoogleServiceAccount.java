package com.navix.common.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * OAuth access tokens for Google APIs (FCM push and Play Integrity, PLAN Phase 8) from a service-account JSON
 * key. The key is read from the file at {@code navix.google.service-account-path}
 * ({@code NAVIX_GOOGLE_SERVICE_ACCOUNT_PATH}); it is never committed and never logged. Blank path = not
 * configured, and callers report that instead of calling Google.
 *
 * <p>The standard two-legged flow: a JWT signed RS256 with the account's private key is exchanged at the
 * account's {@code token_uri} for an hour-long bearer token, cached per scope until a minute before expiry.
 */
@Component
public class GoogleServiceAccount {

    private static final Logger log = LoggerFactory.getLogger(GoogleServiceAccount.class);

    private record Cached(String token, Instant expiresAt) {
    }

    private final String path;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile JsonNode account;

    public GoogleServiceAccount(@Value("${navix.google.service-account-path:}") String path) {
        this.path = path == null ? "" : path.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return !path.isEmpty();
    }

    /** The Firebase / Google Cloud project the key belongs to (FCM's URL needs it). */
    public String projectId() {
        return account().path("project_id").asText();
    }

    /** A bearer token for {@code scope}. Throws {@link IllegalStateException} when not configured or refused. */
    public String accessToken(String scope) {
        Cached c = cache.get(scope);
        if (c != null && Instant.now().isBefore(c.expiresAt().minusSeconds(60))) {
            return c.token();
        }
        JsonNode a = account();
        String tokenUri = a.path("token_uri").asText("https://oauth2.googleapis.com/token");
        Instant now = Instant.now();
        String assertion = Jwts.builder()
                .issuer(a.path("client_email").asText())
                .claim("scope", scope)
                .audience().add(tokenUri).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(privateKey(a.path("private_key").asText()), Jwts.SIG.RS256)
                .compact();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", assertion);
        JsonNode resp = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JsonNode.class);
        if (resp == null || !resp.hasNonNull("access_token")) {
            throw new IllegalStateException("Google token endpoint returned no access_token");
        }
        Cached fresh = new Cached(resp.get("access_token").asText(),
                now.plusSeconds(resp.path("expires_in").asLong(3600)));
        cache.put(scope, fresh);
        return fresh.token();
    }

    private JsonNode account() {
        if (!isConfigured()) {
            throw new IllegalStateException("navix.google.service-account-path is not set");
        }
        JsonNode a = account;
        if (a == null) {
            try {
                a = json.readTree(Files.readString(Path.of(path)));
            } catch (Exception e) {
                // The message names the file only, never its content.
                log.warn("Google service account key unreadable at the configured path: {}", e.getClass().getSimpleName());
                throw new IllegalStateException("Google service account key unreadable", e);
            }
            account = a;
        }
        return a;
    }

    private static PrivateKey privateKey(String pem) {
        try {
            String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            throw new IllegalStateException("Google service account private key is not a PKCS#8 RSA key", e);
        }
    }
}
