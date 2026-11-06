package com.navix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.auth.AccountDeletionService;
import com.navix.common.outbox.Outbox;
import com.navix.common.outbox.OutboxHandler;
import com.navix.common.security.JwtService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN Phase 9 over the full app and a real Postgres: PII encrypted at rest with working lookups and audited full
 * views (CC-25), server-generated customer ids and locked details (CC-24), account deletion and the purge (CC-45),
 * the outbox's retries (EXT-22) and the new foreign keys.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@TestPropertySource(properties = {"navix.sms.mock=true", "navix.storage.bucket=dhannow-it-documents"})
class HardeningIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /** Fails until told to succeed: the outbox retry test's side effect. */
    static final AtomicInteger FLAKY_FAILURES_LEFT = new AtomicInteger();
    static final AtomicInteger FLAKY_RUNS = new AtomicInteger();

    @TestConfiguration
    static class FlakyHandler {
        @Bean
        OutboxHandler flakyOutboxHandler() {
            return new OutboxHandler() {
                @Override
                public Set<String> kinds() {
                    return Set.of("IT_FLAKY");
                }

                @Override
                public void handle(String kind, String payloadJson) {
                    FLAKY_RUNS.incrementAndGet();
                    if (FLAKY_FAILURES_LEFT.getAndDecrement() > 0) {
                        throw new IllegalStateException("provider down");
                    }
                }
            };
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Outbox outbox;
    @Autowired private AccountDeletionService deletions;

    @BeforeEach
    void openApplications() {
        jdbc.update("update feature_flag set enabled = false where flag_key = 'onboarding-paused'");
    }

    // ---- PII ----

    /**
     * Aadhaar, PAN and the salary account are ciphertext in the table, the duplicate-PAN rule still sees the number
     * (CC-25), a full PAN still finds the customer, and staff see it masked unless their role has PII_VIEW, in which
     * case the read is written to pii_access_log.
     */
    @Test
    void cc25_piiIsEncryptedAtRestLookupsWorkAndFullViewsAreAudited() throws Exception {
        long app = createApplication(910001L);
        saveProfile(app, 910001L, "ABCDE1234F", "234567890124", "50100012345678").andExpect(status().isOk());

        var raw = jdbc.queryForMap("select pan, pan_hash, aadhaar, aadhaar_hash, salary_account_number"
                + " from customer_profile where application_id = ?", app);
        assertThat((String) raw.get("pan")).startsWith("pii1:").doesNotContain("ABCDE1234F");
        assertThat((String) raw.get("aadhaar")).startsWith("pii1:").doesNotContain("9012");
        assertThat((String) raw.get("salary_account_number")).startsWith("pii1:").doesNotContain("5678");
        assertThat((String) raw.get("pan_hash")).hasSize(64);
        assertThat((String) raw.get("aadhaar_hash")).hasSize(64);

        // CC-25: the same PAN on another customer is refused, found through the blind index.
        long other = createApplication(910002L);
        saveProfile(other, 910002L, "abcde1234f", null, null)
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_PAN"));

        // The borrower reads their own in full.
        mvc.perform(get("/api/applications/{id}/profile", app).header("Authorization", borrower(910001L)))
                .andExpect(jsonPath("$.data.pan").value("ABCDE1234F"))
                .andExpect(jsonPath("$.data.aadhaar").value("234567890124"));

        // Staff without PII_VIEW: masked, and nothing to audit.
        mvc.perform(get("/api/applications/{id}/profile", app).header("Authorization", staff("31", "COLLECTION_HEAD")))
                .andExpect(jsonPath("$.data.pan").value("ABXXXXX34F"))
                .andExpect(jsonPath("$.data.aadhaar").value("XXXXXXXX0124"))
                .andExpect(jsonPath("$.data.salaryAccountNumber").value("XXXXXXXXXX5678"));
        assertThat(count("select count(*) from pii_access_log")).isZero();

        // PII_VIEW (ADMIN): in full, one audit row naming the fields and the URL, never the values.
        mvc.perform(get("/api/applications/{id}/profile", app).header("Authorization", staff("1", "ADMIN")))
                .andExpect(jsonPath("$.data.pan").value("ABCDE1234F"));
        var log = jdbc.queryForMap("select staff_id, role, path, fields from pii_access_log");
        assertThat(log.get("staff_id")).isEqualTo("1");
        assertThat(log.get("role")).isEqualTo("ADMIN");
        assertThat((String) log.get("path")).isEqualTo("/api/applications/" + app + "/profile");
        assertThat((String) log.get("fields")).isEqualTo("aadhaar,account,pan");

        // A full PAN still finds the customer in the staff search.
        JsonNode page = data(mvc.perform(get("/api/customers/page").param("q", "ABCDE1234F")
                .header("Authorization", staff("1", "ADMIN"))));
        assertThat(page.toString()).contains("910001");
    }

    // ---- CC-24 ----

    /** Two numbers sharing their last seven digits are two customers, each with a DN id from the sequence. */
    @Test
    void cc24_customerIdsAreServerGeneratedAndDetailsCannotBeChanged() throws Exception {
        JsonNode a = login("9819000001");
        JsonNode b = login("9919000001");
        assertThat(a.get("customerId").asLong()).isNotEqualTo(b.get("customerId").asLong());
        assertThat(a.get("customerCode").asText()).matches("DN\\d{7}");
        assertThat(login("9819000001").get("customerId").asLong()).isEqualTo(a.get("customerId").asLong());
        assertThat(count("select count(*) from borrower_mobile where mobile in ('9819000001', '9919000001')")).isEqualTo(2);

        long customerId = a.get("customerId").asLong();
        long app = createApplication(customerId);
        mvc.perform(put("/api/applications/{id}/profile/self", app).header("Authorization", borrower(customerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"Somewhere else\"}"))
                .andExpect(jsonPath("$.error.code").value("PROFILE_LOCKED"));
    }

    // ---- CC-45 ----

    @Test
    void cc45_accountDeletionWaitsForTheLoanThenClosesSignInAndIsPurgedAfterRetention() throws Exception {
        JsonNode session = login("9822200001");
        long customerId = session.get("customerId").asLong();
        long app = createApplication(customerId);
        saveProfile(app, customerId, "PQRST6789K", null, "60200012345678").andExpect(status().isOk());

        jdbc.update("update loan_application set status = 'SANCTIONED' where id = ?", app);
        mvc.perform(post("/api/me/account-deletion").header("Authorization", borrower(customerId)))
                .andExpect(jsonPath("$.error.code").value("LOAN_OPEN"));

        jdbc.update("update loan_application set status = 'CANCELLED' where id = ?", app);
        JsonNode request = data(mvc.perform(post("/api/me/account-deletion").header("Authorization", borrower(customerId))));
        assertThat(request.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(LocalDate.parse(request.get("purgeAfter").asText())).isAfter(LocalDate.now().plusYears(4));
        // Idempotent; sessions ended; sign-in closed; records kept.
        mvc.perform(post("/api/me/account-deletion").header("Authorization", borrower(customerId)))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
        assertThat(count("select count(*) from refresh_token where customer_id = " + customerId + " and revoked_at is null"))
                .isZero();
        mvc.perform(post("/api/auth/borrower/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"9822200001\",\"otp\":\"123456\"}"))
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_CLOSED"));
        assertThat(count("select count(*) from customer_profile where application_id = " + app + " and pan is not null"))
                .isEqualTo(1);

        // After the retention period the identity is blanked and the number is free again.
        jdbc.update("update account_deletion_request set purge_after = current_date - 1 where customer_id = ?", customerId);
        assertThat(deletions.purgeDue()).isEqualTo(1);
        var profile = jdbc.queryForMap("select pan, pan_hash, full_name, mobile, salary_account_number from customer_profile"
                + " where application_id = ?", app);
        assertThat(profile.values()).containsOnlyNulls();
        assertThat(count("select count(*) from borrower_mobile where customer_id = " + customerId)).isZero();
        assertThat(jdbc.queryForObject("select status from account_deletion_request where customer_id = ?", String.class,
                customerId)).isEqualTo("PURGED");
        assertThat(deletions.purgeDue()).isZero();
    }

    // ---- outbox (EXT-22) ----

    @Test
    void ext22_theOutboxRetriesWithBackoffAndAlertsAfterThreeFailures() {
        FLAKY_FAILURES_LEFT.set(3);
        FLAKY_RUNS.set(0);
        outbox.enqueue("IT_FLAKY", "{\"n\":1}"); // no transaction: the first try runs at once, and fails
        long id = jdbc.queryForObject("select max(id) from outbox where kind = 'IT_FLAKY'", Long.class);
        var row = jdbc.queryForMap("select attempts, done_at, last_error,"
                + " next_attempt_at > now() + interval '50 seconds' as backed_off from outbox where id = ?", id);
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("done_at")).isNull();
        assertThat((String) row.get("last_error")).contains("provider down");
        assertThat(row.get("backed_off")).isEqualTo(true);
        assertThat(outbox.runDue(10)).isZero(); // not due yet

        for (int attempt = 2; attempt <= 4; attempt++) {
            jdbc.update("update outbox set next_attempt_at = now() where id = ?", id);
            assertThat(outbox.runDue(10)).isEqualTo(1);
        }
        row = jdbc.queryForMap("select attempts, done_at, last_error from outbox where id = ?", id);
        assertThat(row.get("attempts")).isEqualTo(4);
        assertThat(row.get("done_at")).isNotNull();
        assertThat(row.get("last_error")).isNull();
        assertThat(FLAKY_RUNS.get()).isEqualTo(4);
        assertThat(outbox.runDue(10)).isZero();
    }

    // ---- foreign keys ----

    @Test
    void theNewForeignKeysAreInPlace() {
        List<String> fks = jdbc.queryForList("select constraint_name from information_schema.table_constraints"
                + " where constraint_type = 'FOREIGN KEY' and constraint_name in ('fk_loan_extension_collection_case',"
                + " 'fk_complaint_assigned_to', 'fk_refresh_token_borrower', 'fk_device_token_borrower')", String.class);
        assertThat(fks).hasSize(4);
    }

    // ---- helpers ----

    private JsonNode login(String mobile) throws Exception {
        return data(mvc.perform(post("/api/auth/borrower/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mobile\":\"" + mobile + "\",\"otp\":\"123456\",\"deviceId\":\"device-" + mobile + "\"}")));
    }

    private long createApplication(long customerId) throws Exception {
        return data(mvc.perform(post("/api/applications").header("Authorization", borrower(customerId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":" + customerId + "}")))
                .get("id").asLong();
    }

    private ResultActions saveProfile(long app, long customerId, String pan, String aadhaar, String account)
            throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("fullName", "Asha Kumari");
        body.put("pan", pan);
        if (aadhaar != null) {
            body.put("aadhaar", aadhaar);
        }
        if (account != null) {
            body.put("salaryAccountNumber", account);
            body.put("salaryIfsc", "HDFC0000123");
        }
        return mvc.perform(put("/api/applications/{id}/profile", app).header("Authorization", borrower(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private JsonNode data(ResultActions result) throws Exception {
        return om.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private String borrower(long customerId) {
        return "Bearer " + jwt.issue(String.valueOf(customerId), "Borrower", "BORROWER", JwtService.AUDIENCE_BORROWER);
    }

    private String staff(String id, String role) {
        return "Bearer " + jwt.issue(id, "Staff " + id, role, JwtService.AUDIENCE_STAFF);
    }
}
