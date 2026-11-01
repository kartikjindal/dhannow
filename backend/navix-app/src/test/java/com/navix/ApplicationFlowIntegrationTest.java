package com.navix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.security.JwtService;
import com.navix.loan.service.ApplicationVerificationService;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.repository.ApplicationVerificationRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test of the application state machine over the FULL app:
 * real controllers → services → Flyway-migrated Postgres (Testcontainers), now over the real
 * JWT auth chain (P6). Each actor is a minted bearer token; the onboarding verification gate
 * (P3) is mocked-PASS here so this test stays focused on the maker-checker lifecycle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class ApplicationFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JwtService jwt;
    @Autowired
    private ApplicationVerificationRepository verificationRepository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private com.navix.loan.repository.CustomerProfileRepository profileRepository;

    /** The onboarding completeness gate is exercised by unit tests; here it is PASS so the
     *  lifecycle test reaches KYC_PENDING without seeding every external verification. */
    @MockBean
    private ApplicationVerificationService verificationService;

    @BeforeEach
    void allowSubmitKyc() {
        when(verificationService.allRequiredPassed(anyLong())).thenReturn(true);
        when(verificationService.intakeCardsComplete(anyLong())).thenReturn(true);
        // V76 seeds onboarding-paused ON (the business decision for prod); this test walks the full
        // intake lifecycle, which that flag deliberately refuses. Dev-only flags are SQL-only.
        jdbc.update("update feature_flag set enabled = false where flag_key = 'onboarding-paused'");
    }

    @Test
    void customerAppliesAndStaffWalksItToActive() throws Exception {
        long appId = createApplication(7L);
        assertStatusEquals(appId, "DRAFT");

        act(appId, "submit-kyc", "7", "BORROWER", null, "KYC_PENDING");
        // V45: the Credit Head assigns straight off the submitted intake and the executive's
        // sanction is final — no KYC approver, no Head counter-approval.
        act(appId, "assign", "5", "CREDIT_HEAD", "{\"executiveId\":2}", "CREDIT_EXEC_PENDING");
        // DhanNow: a first loan is level 1, so the sanction is the ₹1,000 ladder amount (PLAN Phase 2).
        act(appId, "sanction", "2", "CREDIT_EXECUTIVE",
                "{\"sanctionedAmountPaise\":100000,\"salaryCreditDay\":28,\"remarks\":\"verified\"}", "SANCTIONED");
        recordEsign(appId);
        act(appId, "accept-offer", "7", "BORROWER", "{\"amountPaise\":100000}", "DISBURSEMENT_PENDING");
        // V48: the Disbursement Head releases directly and their transaction id IS the validation —
        // there is no accountant hop behind them, so this one call lands the loan ACTIVE.
        act(appId, "disbursement-decision", "6", "DISBURSEMENT_HEAD",
                "{\"decision\":true,\"txnRef\":\"UTR-IT-001\"}", "ACTIVE");

        MvcResult appView = mvc.perform(get("/api/applications/{id}", appId)
                        .header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loanId", notNullValue()))
                .andReturn();
        long loanId = data(appView).get("loanId").asLong();

        // DhanNow v1 (V92 seed): ₹1,000 → ₹742 in the bank, ₹1,000 repaid on day 14.
        mvc.perform(get("/api/loan/{id}", loanId).header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.netDisbursedPaise").value(74200))
                .andExpect(jsonPath("$.data.totalRepayablePaise").value(100000))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                // PLAN Phase 3: the persisted lifecycle, hold and repayment state ride along additively.
                .andExpect(jsonPath("$.data.lifecycleStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.holdStatus").value("NONE"))
                .andExpect(jsonPath("$.data.repaymentStatus").value("UNPAID"));
        assertThat(jdbc.queryForMap("select l.pricing_version_id, l.upfront_interest_paise, l.tenure_days,"
                        + " a.pricing_version_id as app_pricing from loan l join loan_application a on a.loan_id = l.id"
                        + " where l.id = ?", loanId))
                .containsEntry("pricing_version_id", 1L)
                .containsEntry("app_pricing", 1L)
                .containsEntry("upfront_interest_paise", 14000L)
                .containsEntry("tenure_days", 14);

        mvc.perform(get("/api/me/credit-level").header("Authorization", bearer("7", "BORROWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.level").value(1))
                .andExpect(jsonPath("$.data.maxPaise").value(100000))
                .andExpect(jsonPath("$.data.nextLevelPaise").value(200000))
                .andExpect(jsonPath("$.data.ladder.length()").value(7));

        mvc.perform(get("/api/applications/{id}/events", appId).header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.action=='ACTIVATE')]").exists());
    }

    @Test
    void adminWalksApplicationToActiveSolo() throws Exception {
        long appId = createApplication(10L);
        assertStatusEquals(appId, "DRAFT");

        act(appId, "submit-kyc", "10", "BORROWER", null, "KYC_PENDING");
        act(appId, "assign", "10", "ADMIN", "{\"executiveId\":10}", "CREDIT_EXEC_PENDING");
        act(appId, "sanction", "10", "ADMIN",
                "{\"sanctionedAmountPaise\":100000,\"salaryCreditDay\":28}",
                "SANCTIONED");
        recordEsign(appId);
        act(appId, "accept-offer", "10", "BORROWER", "{\"amountPaise\":100000}", "DISBURSEMENT_PENDING");
        act(appId, "disbursement-decision", "10", "ADMIN",
                "{\"decision\":true,\"txnRef\":\"ADMIN-TXN-10\"}", "ACTIVE");

        mvc.perform(get("/api/applications/{id}", appId).header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.loanId", notNullValue()));
    }

    /**
     * DhanNow (CC-01): the due date is 14 days from the disbursal date, whatever the salary day (navix
     * used the latest salary credit within 40 days).
     */
    @Test
    void cc01_dueDateIsFourteenDaysFromTheDisbursal() throws Exception {
        long appId = createApplication(8L);
        int salaryDay = 17;
        LocalDate repay = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(14);

        act(appId, "submit-kyc", "8", "BORROWER", null, "KYC_PENDING");
        act(appId, "assign", "5", "CREDIT_HEAD", "{\"executiveId\":2}", "CREDIT_EXEC_PENDING");
        act(appId, "sanction", "2", "CREDIT_EXECUTIVE",
                "{\"sanctionedAmountPaise\":100000,\"salaryCreditDay\":" + salaryDay + "}", "SANCTIONED");
        recordEsign(appId);
        act(appId, "accept-offer", "8", "BORROWER", "{\"amountPaise\":100000}", "DISBURSEMENT_PENDING");
        act(appId, "disbursement-decision", "6", "DISBURSEMENT_HEAD",
                "{\"decision\":true,\"txnRef\":\"UTR-8\"}", "ACTIVE");

        MvcResult appView = mvc.perform(get("/api/applications/{id}", appId)
                        .header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andReturn();
        long loanId = data(appView).get("loanId").asLong();

        mvc.perform(get("/api/loan/{id}", loanId).header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dueDate").value(repay.toString()));
    }

    /** A rejected lead lands in the staff-only register tagged MANUAL (revamp.md decision 31). */
    @Test
    void rejectLeadIsRecordedInTheRejectionRegister() throws Exception {
        long appId = createApplication(20L);
        act(appId, "submit-kyc", "20", "BORROWER", null, "KYC_PENDING");
        act(appId, "assign", "5", "CREDIT_HEAD", "{\"executiveId\":2}", "CREDIT_EXEC_PENDING");
        act(appId, "reject-lead", "2", "CREDIT_EXECUTIVE", "{\"notes\":\"income unverifiable\"}", "REJECTED");

        mvc.perform(get("/api/applications/rejections").param("reason", "MANUAL")
                        .header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.applicationId==" + appId + ")]").exists());
    }

    /** PLAN Phase 2: a first-time customer is level 1, so a ₹2,000 sanction is refused. */
    @Test
    void sanctionAboveTheCreditLevelIsRefused() throws Exception {
        long appId = createApplication(11L);
        act(appId, "submit-kyc", "11", "BORROWER", null, "KYC_PENDING");
        act(appId, "assign", "5", "CREDIT_HEAD", "{\"executiveId\":2}", "CREDIT_EXEC_PENDING");
        mvc.perform(post("/api/applications/{id}/sanction", appId)
                        .header("Authorization", bearer("2", "CREDIT_EXECUTIVE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sanctionedAmountPaise\":200000,\"salaryCreditDay\":20}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("LIMIT_EXCEEDED"));
        assertStatusEquals(appId, "CREDIT_EXEC_PENDING");
    }

    /** CC-03 at the database: a version's terms cannot be edited once it is live; add a new version. */
    @Test
    void cc03_liveVersionTermsAreFrozenInTheDatabase() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.update("update pricing_config set pf_rate = 0.12 where version = 1"))
                .hasMessageContaining("terms are frozen");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "update product_config set allowed_amounts_paise = '{100000}' where version = 1"))
                .hasMessageContaining("terms are frozen");
    }

    /** PLAN Phase 2: a manual credit-level exception is ADMIN-only and needs a reason code. */
    @Test
    void creditLevelManualExceptionIsAdminOnly() throws Exception {
        mvc.perform(post("/api/customers/{id}/credit-level", 12)
                        .header("Authorization", bearer("5", "CREDIT_HEAD"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":3,\"reasonCode\":\"RISK_REVIEW\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        mvc.perform(post("/api/customers/{id}/credit-level", 12)
                        .header("Authorization", bearer("1", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":3,\"reasonCode\":\"RISK_REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.level").value(3))
                .andExpect(jsonPath("$.data.maxPaise").value(300000));
        assertThat(jdbc.queryForObject("select count(*) from credit_level_history where customer_id = 12"
                + " and reason = 'MANUAL_EXCEPTION' and approved_by = 1 and reason_code = 'RISK_REVIEW'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void illegalTransitionIsRejected() throws Exception {
        long appId = createApplication(9L);
        mvc.perform(post("/api/applications/{id}/sanction", appId)
                        .header("Authorization", bearer("10", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sanctionedAmountPaise\":100000,\"salaryCreditDay\":20}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_TRANSITION"));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/applications/{id}", 1).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers -------------------------------------------------------------------

    private String bearer(String id, String role) {
        String audience = "BORROWER".equals(role) ? JwtService.AUDIENCE_BORROWER : JwtService.AUDIENCE_STAFF;
        return "Bearer " + jwt.issue(id, "Actor " + id, role, audience);
    }

    /** The duplicate-Aadhaar rule's lookup, on real Postgres: only another customer's SUBMITTED file counts. */
    @Test
    void duplicateAadhaarLookupMatchesOnlyOtherCustomersSubmittedApplications() throws Exception {
        String aadhaar = "234567890124";
        long submitted = createApplication(41L);
        act(submitted, "submit-kyc", "41", "BORROWER", null, "KYC_PENDING");
        long draftOnly = createApplication(42L);
        long mine = createApplication(43L);
        for (long id : new long[] {submitted, draftOnly, mine}) {
            jdbc.update("insert into customer_profile (application_id, full_name, aadhaar, created_at, created_by)"
                    + " values (?, 'Dup Test', ?, now(), 'test')", id, aadhaar);
        }

        assertThat(profileRepository.findSubmittedApplicationIdsByAadhaarForOtherCustomers(aadhaar, 43L))
                .containsExactly(submitted);
        // The submitted file's own customer never matches itself.
        assertThat(profileRepository.findSubmittedApplicationIdsByAadhaarForOtherCustomers(aadhaar, 41L)).isEmpty();
        // An earlier file rejected for identity fraud is not evidence of who owns the number.
        jdbc.update("insert into application_rejection (application_id, customer_id, reason_code, auto, created_at, created_by)"
                + " values (?, 41, 'FRAUD_REJECTED', true, now(), 'test')", submitted);
        assertThat(profileRepository.findSubmittedApplicationIdsByAadhaarForOtherCustomers(aadhaar, 43L)).isEmpty();
    }

    private long createApplication(long customerId) throws Exception {
        MvcResult result = mvc.perform(post("/api/applications")
                        .header("Authorization", bearer(String.valueOf(customerId), "BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        return data(result).get("id").asLong();
    }

    private void recordEsign(long appId) {
        ApplicationVerification row = new ApplicationVerification();
        row.setApplicationId(appId);
        row.setCheckType(ApplicationVerificationService.ESIGN);
        row.setStatus(ApplicationVerificationService.PASS);
        row.setProvider("TEST");
        verificationRepository.save(row);
    }

    private void act(long appId, String action, String actorId, String role, String body,
                     String expectedStatus) throws Exception {
        var request = post("/api/applications/{id}/{action}", appId, action)
                .header("Authorization", bearer(actorId, role))
                .contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.content(body);
        }
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expectedStatus));
    }

    private void assertStatusEquals(long appId, String expected) throws Exception {
        mvc.perform(get("/api/applications/{id}", appId).header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expected));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return om.readTree(result.getResponse().getContentAsString()).get("data");
    }
}
