package com.navix.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.common.google.GoogleServiceAccount;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link IntegrityVerifier} over Google's {@code decodeIntegrityToken} API, authenticated with the service
 * account in {@code NAVIX_GOOGLE_SERVICE_ACCOUNT_PATH}. Without one every token fails (and is logged), so the
 * {@code play-integrity} flag cannot be switched on by accident in an environment that has no key.
 */
@Component
public class PlayIntegrityClient implements IntegrityVerifier {

    private static final Logger log = LoggerFactory.getLogger(PlayIntegrityClient.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/playintegrity";
    /** A token older than this is refused: the app asks for a fresh one per sign-in. */
    static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final GoogleServiceAccount google;
    private final String packageName;
    private final RestClient http;

    public PlayIntegrityClient(GoogleServiceAccount google,
                               @Value("${navix.integrity.package-name:com.dhannow.app}") String packageName) {
        this.google = google;
        this.packageName = packageName;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(factory)
                .baseUrl("https://playintegrity.googleapis.com/v1/").build();
    }

    @Override
    public boolean passes(String integrityToken) {
        if (integrityToken == null || integrityToken.isBlank() || integrityToken.length() > 16_384) {
            return false;
        }
        if (!google.isConfigured()) {
            log.warn("play integrity check failed: no Google service account configured");
            return false;
        }
        try {
            JsonNode resp = http.post().uri(packageName + ":decodeIntegrityToken")
                    .header("Authorization", "Bearer " + google.accessToken(SCOPE))
                    .body(Map.of("integrity_token", integrityToken))
                    .retrieve().body(JsonNode.class);
            return judge(resp == null ? null : resp.path("tokenPayloadExternal"), packageName, Instant.now());
        } catch (RuntimeException e) {
            // Fail closed: unlike Turnstile (where an outage fails open), the Android path has no second check
            // behind it except the limiter. A borrower can still sign in with an OTP (CC-42).
            log.warn("play integrity check failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * The verdict rule: requested by our package, recognised by Play as our app, on a device that meets device
     * integrity, minted in the last {@link #MAX_AGE}.
     */
    static boolean judge(JsonNode payload, String packageName, Instant now) {
        if (payload == null || payload.isMissingNode()) {
            return false;
        }
        JsonNode req = payload.path("requestDetails");
        long minted = req.path("timestampMillis").asLong(0);
        boolean fresh = minted > 0 && Duration.between(Instant.ofEpochMilli(minted), now).abs().compareTo(MAX_AGE) <= 0;
        boolean ourPackage = packageName.equals(req.path("requestPackageName").asText())
                && packageName.equals(payload.path("appIntegrity").path("packageName").asText());
        boolean recognised = "PLAY_RECOGNIZED".equals(payload.path("appIntegrity").path("appRecognitionVerdict").asText());
        boolean device = false;
        for (JsonNode v : payload.path("deviceIntegrity").path("deviceRecognitionVerdict")) {
            device |= "MEETS_DEVICE_INTEGRITY".equals(v.asText());
        }
        return fresh && ourPackage && recognised && device;
    }
}
