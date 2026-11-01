package com.navix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.auth.AttemptLimiter;
import com.navix.auth.BorrowerOtpService;
import com.navix.auth.DeviceService;
import com.navix.auth.JdbcAuthStateStore;
import com.navix.common.brand.BrandProperties;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.JwtService;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.sms.SmsProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN Phase 8 over the full app and a real Postgres: app sessions (refresh rotation, reuse, logout, devices), OTP and
 * limiter state shared across instances, the redirect allowlist, guided selfie and its staff queue, CORS and
 * {@code GET /api/app/config}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestPropertySource(properties = {"navix.sms.mock=true", "navix.storage.bucket=dhannow-it-documents",
        "navix.brand.name=DhanNow", "navix.brand.support-phone=9211331169",
        "navix.grievance.officer-email=grievance@dhannow.com"})
class MobilePlatformIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private DeviceService deviceService;

    @BeforeEach
    void openApplications() {
        jdbc.update("update feature_flag set enabled = false where flag_key = 'onboarding-paused'");
    }

    // ---- refresh tokens, logout, devices ----

    /** CC-39: every refresh rotates; a spent token sent again revokes the whole family; another device's use too. */
    @Test
    void cc39_refreshRotatesAndReuseRevokesTheFamily() throws Exception {
        JsonNode first = login("9811100001", "device-cc39-aaaa");
        assertThat(first.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(jwt.verify(first.get("token").asText()).deviceId()).isEqualTo("device-cc39-aaaa");

        JsonNode second = refresh(first.get("refreshToken").asText(), "device-cc39-aaaa");
        assertThat(second.get("refreshToken").asText()).isNotEqualTo(first.get("refreshToken").asText());
        long customerId = first.get("customerId").asLong();
        assertThat(second.get("customerId").asLong()).isEqualTo(customerId);
        assertThat(first.get("customerCode").asText()).isEqualTo(String.format("DN%07d", customerId)); // CC-24
        JsonNode third = refresh(second.get("refreshToken").asText(), "device-cc39-aaaa");

        // The first token again: theft. The family dies, so even the newest token is refused.
        expectCode(refreshRequest(first.get("refreshToken").asText(), "device-cc39-aaaa"), "REFRESH_REUSED");
        expectCode(refreshRequest(third.get("refreshToken").asText(), "device-cc39-aaaa"), "REFRESH_INVALID");
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where customer_id = ?"
                + " and revoked_at is null", Long.class, customerId)).isZero();

        // A token presented from another device is refused and its family revoked.
        JsonNode other = login("9811100001", "device-cc39-bbbb");
        expectCode(refreshRequest(other.get("refreshToken").asText(), "device-cc39-zzzz"), "REFRESH_INVALID");
        expectCode(refreshRequest(other.get("refreshToken").asText(), "device-cc39-bbbb"), "REFRESH_INVALID");
        expectCode(refreshRequest("not-a-token", "device-cc39-bbbb"), "REFRESH_INVALID");
    }

    @Test
    void logoutEndsThisDevicesSession() throws Exception {
        JsonNode s = login("9811100002", "device-logout-01");
        mvc.perform(post("/api/auth/borrower/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + s.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isOk());
        expectCode(refreshRequest(s.get("refreshToken").asText(), "device-logout-01"), "REFRESH_INVALID");
        // Idempotent, and harmless with nothing to sign out.
        mvc.perform(post("/api/auth/borrower/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + s.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/borrower/logout")).andExpect(status().isOk());
    }

    /** CC-38: two phones, each with its own session; sign one out from the other; then log out of all devices. */
    @Test
    void cc38_devicesListSignOutADeviceAndLogOutOfAll() throws Exception {
        JsonNode a = login("9811100003", "device-cc38-phone-a");
        JsonNode b = login("9811100003", "device-cc38-phone-b");
        String bearerA = "Bearer " + a.get("token").asText();

        mvc.perform(put("/api/devices/me").header("Authorization", bearerA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fcmToken\":\"fcm-token-phone-a\",\"platform\":\"android\",\"appVersion\":\"1.0.1\"}"))
                .andExpect(status().isOk());
        // A device can only register itself.
        expectCode(put("/api/devices/me").header("Authorization", bearerA).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"device-cc38-phone-b\",\"fcmToken\":\"x\"}"), "DEVICE_MISMATCH");
        long customerId = a.get("customerId").asLong();
        assertThat(b.get("customerId").asLong()).isEqualTo(customerId);
        assertThat(deviceService.fcmTokens(customerId)).containsExactly("fcm-token-phone-a");

        JsonNode list = data(mvc.perform(get("/api/devices/me").header("Authorization", bearerA)));
        assertThat(list).hasSize(2);
        for (JsonNode d : list) {
            assertThat(d.get("current").asBoolean()).isEqualTo(d.get("deviceId").asText().equals("device-cc38-phone-a"));
        }
        assertThat(list.findValuesAsText("appVersion")).contains("1.0.1");
        assertThat(list.findValuesAsText("name")).contains("Pixel 6");

        mvc.perform(delete("/api/devices/me/{id}", "device-cc38-phone-b").header("Authorization", bearerA))
                .andExpect(status().isOk());
        expectCode(refreshRequest(b.get("refreshToken").asText(), "device-cc38-phone-b"), "REFRESH_INVALID");
        assertThat(data(mvc.perform(get("/api/devices/me").header("Authorization", bearerA)))).hasSize(1);

        mvc.perform(post("/api/auth/borrower/logout").header("Authorization", bearerA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"allDevices\":true}"))
                .andExpect(status().isOk());
        expectCode(refreshRequest(a.get("refreshToken").asText(), "device-cc38-phone-a"), "REFRESH_INVALID");
        assertThat(deviceService.fcmTokens(customerId)).isEmpty();

        // Staff cannot use the borrower device API.
        expectCode(get("/api/devices/me").header("Authorization", "Bearer "
                + jwt.issue("1", "Admin", "ADMIN", JwtService.AUDIENCE_STAFF)), "FORBIDDEN_ROLE");
    }

    /** The web (no device id) still gets its one long-lived token and no refresh token. */
    @Test
    void theWebLoginIsUnchanged() throws Exception {
        JsonNode web = data(mvc.perform(post("/api/auth/borrower/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mobile\":\"9811100004\",\"otp\":\"123456\"}")));
        assertThat(web.path("refreshToken").asText(null)).isNull();
        assertThat(jwt.verify(web.get("token").asText()).deviceId()).isNull();
    }

    // ---- OTP and limiter state in Postgres ----

    /**
     * CC-36: OTP codes and counters live in Postgres. A code sent by one instance verifies on a fresh one (a restart,
     * or the next task behind the load balancer); wrong guesses and the limiter count across instances.
     */
    @Test
    void cc36_otpStateSurvivesARestartAndLimitsHoldAcrossInstances() {
        BorrowerOtpService before = otpInstance();
        String code = before.request("9811200001", OtpVerifierPort.LOGIN).devCode();
        assertThat(code).hasSize(6);
        assertThat(jdbc.queryForObject("select count(*) from otp_challenge where code_hash = ?", Long.class, code))
                .as("the code is never stored in clear").isZero();

        BorrowerOtpService after = otpInstance();
        assertThat(after.verify("9811200001", code, OtpVerifierPort.LOGIN)).isTrue();
        assertThat(otpInstance().verify("9811200001", code, OtpVerifierPort.LOGIN)).as("single use").isFalse();

        // Five wrong guesses spread over three instances lock the code everywhere.
        String locked = before.request("9811200002", OtpVerifierPort.LOGIN).devCode();
        for (int i = 0; i < 5; i++) {
            assertThat(otpInstance().verify("9811200002", "000000", OtpVerifierPort.LOGIN)).isFalse();
        }
        assertThat(otpInstance().verify("9811200002", locked, OtpVerifierPort.LOGIN)).isFalse();

        // The limiter: three hits on one instance, the fourth on another is refused.
        AttemptLimiter one = new AttemptLimiter(store());
        AttemptLimiter two = new AttemptLimiter(store());
        for (int i = 0; i < 3; i++) {
            one.hit("it:shared-key", 3, Duration.ofMinutes(15), Duration.ofMinutes(15), "Too many.");
        }
        assertThatThrownBy(() -> two.hit("it:shared-key", 3, Duration.ofMinutes(15), Duration.ofMinutes(15), "Too many."))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("code", "TOO_MANY_ATTEMPTS");
        two.clear("it:shared-key");
        one.hit("it:shared-key", 3, Duration.ofMinutes(15), Duration.ofMinutes(15), "Too many.");
        assertThat(jdbc.queryForObject("select count(*) from attempt_counter where key like 'it:%'", Long.class))
                .as("keys are stored hashed").isZero();
    }

    private JdbcAuthStateStore store() {
        return new JdbcAuthStateStore(jdbc, txManager, "navix-local-dev-secret-change-me");
    }

    private BorrowerOtpService otpInstance() {
        SmsProperties sms = new SmsProperties("https://sms.invalid/", null, null, null, null, null, null, null, null,
                Map.of(), null, null, false, true, 300, 6, false, "123456");
        JdbcAuthStateStore store = store();
        return new BorrowerOtpService(null, null, sms, new AttemptLimiter(store),
                new BrandProperties("DhanNow", "www.dhannow.com", "9211331169", "support@dhannow.com"), store);
    }

    // ---- redirect allowlist, guided selfie and the review queue ----

    /** CC-34: DigiLocker may only return the borrower to the allowlisted app callback pages. */
    @Test
    void cc34_digilockerRedirectToAnotherHostIsRefused() throws Exception {
        long appId = createApplication(1300001L);
        expectCode(post("/api/applications/{id}/verify/digilocker/init", appId)
                .header("Authorization", borrower(1300001L)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"redirectUrl\":\"https://evil.example/app/cb/digilocker.html\"}"), "REDIRECT_NOT_ALLOWED");
    }

    /** Guided selfie → REVIEW → in the staff queue → approve passes it; reject sends the borrower back to retake. */
    @Test
    void selfieFrames_goToTheStaffQueue_approveAndReject() throws Exception {
        long approveApp = sanctionedApplication(1300002L);
        long rejectApp = sanctionedApplication(1300003L);
        submitFrames(approveApp, 1300002L);
        submitFrames(rejectApp, 1300003L);

        String credit = "Bearer " + jwt.issue("5", "Credit Exec", "CREDIT_EXECUTIVE", JwtService.AUDIENCE_STAFF);
        JsonNode queue = data(mvc.perform(get("/api/staff/selfie-reviews").header("Authorization", credit)));
        assertThat(queue.findValuesAsText("applicationId")).contains(String.valueOf(approveApp), String.valueOf(rejectApp));
        JsonNode item = null;
        for (JsonNode q : queue) {
            if (q.get("applicationId").asLong() == approveApp) {
                item = q;
            }
        }
        assertThat(item.get("frames").fieldNames()).toIterable().containsExactly("straight", "right", "left");
        assertThat(item.get("attempts").asInt()).isEqualTo(1);
        // Not the credit team: refused. A borrower token cannot reach the staff namespace at all.
        expectCode(get("/api/staff/selfie-reviews").header("Authorization",
                "Bearer " + jwt.issue("3", "Acc", "ACCOUNTANT", JwtService.AUDIENCE_STAFF)), "FORBIDDEN_ROLE");
        mvc.perform(get("/api/staff/selfie-reviews").header("Authorization", borrower(1300002L)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(401, 403));

        mvc.perform(post("/api/staff/selfie-reviews/{id}/approve", approveApp).header("Authorization", credit)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Same person, live\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PASS"));
        expectCode(post("/api/staff/selfie-reviews/{id}/approve", approveApp).header("Authorization", credit)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), "STALE_STATE");

        expectCode(post("/api/staff/selfie-reviews/{id}/reject", rejectApp).header("Authorization", credit)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), "NOTE_REQUIRED");
        mvc.perform(post("/api/staff/selfie-reviews/{id}/reject", rejectApp).header("Authorization", credit)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Face not visible\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAIL"));
        Map<String, Object> row = jdbc.queryForMap("select status, reopened_at from application_verification"
                + " where application_id = ? and check_type = 'SELFIE'", rejectApp);
        assertThat(row.get("reopened_at")).as("the borrower is sent back to the selfie step").isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from application_event where application_id = ?"
                + " and action = 'VERIFICATION_OVERRIDE'", Long.class, rejectApp)).isEqualTo(1L);
        // Fresh tries after a reject.
        submitFrames(rejectApp, 1300003L);
    }

    /** The server cap: three selfie submissions, then the step waits for staff. */
    @Test
    void selfieFrames_theFourthSubmissionIsRefused() throws Exception {
        long appId = sanctionedApplication(1300004L);
        for (int i = 0; i < 3; i++) {
            submitFrames(appId, 1300004L);
        }
        expectCode(framesRequest(appId, 1300004L), "SELFIE_ATTEMPTS_EXCEEDED");
    }

    private void submitFrames(long appId, long customerId) throws Exception {
        mvc.perform(framesRequest(appId, customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEW"));
    }

    private int frameSeq = 0;

    private RequestBuilder framesRequest(long appId, long customerId) {
        String base = "applications/" + appId + "/selfie/" + (++frameSeq);
        return post("/api/applications/{id}/verify/selfie/frames", appId)
                .header("Authorization", borrower(customerId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"straightKey\":\"" + base + "-s.jpg\",\"rightKey\":\"" + base + "-r.jpg\",\"leftKey\":\""
                        + base + "-l.jpg\"}");
    }

    private long sanctionedApplication(long customerId) throws Exception {
        long appId = createApplication(customerId);
        jdbc.update("update loan_application set status = 'SANCTIONED' where id = ?", appId);
        return appId;
    }

    private long createApplication(long customerId) throws Exception {
        return data(mvc.perform(post("/api/applications").header("Authorization", borrower(customerId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":" + customerId + "}")))
                .get("id").asLong();
    }

    // ---- CORS and app config ----

    @Test
    void cors_allowsTheDhanNowSitesAndRefusesOthers() throws Exception {
        for (String origin : new String[] {"https://dhannow.com", "https://staff.dhannow.com"}) {
            mvc.perform(options("/api/loans").header("Origin", origin).header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
        mvc.perform(options("/api/loans").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /** CC-52: public, before sign-in: versions, flags, contacts from the brand config, legal pages. */
    @Test
    void cc52_appConfigIsPublicAndCarriesVersionsFlagsAndContacts() throws Exception {
        JsonNode cfg = data(mvc.perform(get("/api/app/config")));
        assertThat(cfg.get("minVersion").asText()).isEqualTo("1.0.0");
        assertThat(cfg.get("maintenance").asBoolean()).isFalse();
        assertThat(cfg.get("flags").get("play-integrity").asBoolean()).isFalse();
        assertThat(cfg.get("flags").get("push-fcm").asBoolean()).isFalse();
        assertThat(cfg.get("flags").get("loan-extension").asBoolean()).isTrue();
        assertThat(cfg.get("support").get("phone").asText()).isEqualTo("9211331169");
        assertThat(cfg.get("grievance").get("email").asText()).isEqualTo("grievance@dhannow.com");
        assertThat(cfg.get("legal").get(0).get("url").asText()).isEqualTo("https://dhannow.com/terms.html");
        assertThat(cfg.get("legal").findValuesAsText("key")).contains("privacy", "kfs-policy", "repayment-policy");
    }

    // ---- helpers ----

    private JsonNode login(String mobile, String deviceId) throws Exception {
        return data(mvc.perform(post("/api/auth/borrower/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mobile\":\"" + mobile + "\",\"otp\":\"123456\",\"deviceId\":\"" + deviceId
                        + "\",\"deviceName\":\"Pixel 6\",\"appVersion\":\"1.0.0\"}")));
    }

    private JsonNode refresh(String refreshToken, String deviceId) throws Exception {
        return data(mvc.perform(refreshRequest(refreshToken, deviceId)));
    }

    private RequestBuilder refreshRequest(String refreshToken, String deviceId) {
        return post("/api/auth/borrower/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\",\"deviceId\":\"" + deviceId + "\"}");
    }

    private JsonNode data(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("data");
    }

    private void expectCode(RequestBuilder request, String code) throws Exception {
        mvc.perform(request).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value(code));
    }

    private String borrower(long customerId) {
        return "Bearer " + jwt.issue(String.valueOf(customerId), "Borrower", "BORROWER", JwtService.AUDIENCE_BORROWER);
    }
}
