package com.navix.notification.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.navix.common.google.GoogleServiceAccount;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * {@link PushClient} over the FCM HTTP v1 API, authenticated with the Google service account at
 * {@code NAVIX_GOOGLE_SERVICE_ACCOUNT_PATH} (never committed). Without one every send fails as
 * {@code FCM_NOT_CONFIGURED}, recorded on the delivery row like any other failure.
 */
@Component
public class FcmPushClient implements PushClient {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final GoogleServiceAccount google;
    private final RestClient http;

    public FcmPushClient(GoogleServiceAccount google) {
        this.google = google;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).baseUrl("https://fcm.googleapis.com/v1/").build();
    }

    @Override
    public PushResult send(String fcmToken, String title, String body, String type) {
        if (!google.isConfigured()) {
            return new PushResult(false, false, null, "FCM_NOT_CONFIGURED");
        }
        Map<String, Object> message = Map.of("message", Map.of(
                "token", fcmToken,
                "notification", Map.of("title", title == null ? "" : title, "body", body == null ? "" : body),
                "data", Map.of("type", type == null ? "" : type),
                "android", Map.of("priority", "high")));
        try {
            JsonNode resp = http.post().uri("projects/" + google.projectId() + "/messages:send")
                    .header("Authorization", "Bearer " + google.accessToken(SCOPE))
                    .body(message).retrieve().body(JsonNode.class);
            return new PushResult(true, false, resp == null ? null : resp.path("name").asText(null), null);
        } catch (HttpStatusCodeException e) {
            // FCM answers 404 UNREGISTERED (or 400 INVALID_ARGUMENT for a malformed token) when the token is dead.
            String text = e.getResponseBodyAsString();
            boolean dead = e.getStatusCode().value() == 404 || text.contains("UNREGISTERED");
            return new PushResult(false, dead, null, "FCM_HTTP_" + e.getStatusCode().value());
        } catch (RuntimeException e) {
            return new PushResult(false, false, null, "FCM_" + e.getClass().getSimpleName());
        }
    }
}
