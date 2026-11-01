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
// OTP mock mode (fixed code, no SMS) so the extension confirmation can be driven end to end.
@org.springframework.test.context.TestPropertySource(properties = "navix.sms.mock=true")
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
    @Autowired
    private com.navix.loan.service.ComplaintService complaints;

    /** The onboarding completeness gate is exercised by unit tests; here it is PASS so the
     *  lifecycle test reaches KYC_PENDING without seeding every external verification. */
    @MockBean
    private ApplicationVerificationService verificationService;

    /** S3 is offline in tests; documents are stored "nowhere" and read back as a stub PDF. */
    @MockBean
    private com.navix.common.storage.DocumentStoragePort storage;

    @BeforeEach
    void allowSubmitKyc() {
        when(verificationService.allRequiredPassed(anyLong())).thenReturn(true);
        when(verificationService.intakeCardsComplete(anyLong())).thenReturn(true);
        // V76 seeds onboarding-paused ON (the business decision for prod); this test walks the full
        // intake lifecycle, which that flag deliberately refuses. Dev-only flags are SQL-only.
        jdbc.update("update feature_flag set enabled = false where flag_key = 'onboarding-paused'");
        when(storage.presignDownload(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(i -> "https://s3.test/" + i.getArgument(0));
        when(storage.presignDownload(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> "https://s3.test/" + i.getArgument(0));
        when(storage.fetch(org.mockito.ArgumentMatchers.anyString())).thenReturn("%PDF-signed".getBytes());
    }

    // ---- PLAN Phase 6a: payments, closure, schedules ---------------------------------

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Confirmation writes the v1 BULLET schedule; a part and then a full on-time payment (one replayed with
     * its Idempotency-Key) are verified, split penal → principal, close the loan, issue the closure receipt
     * and move the borrower up one level.
     */
    @Test
    void payments_recordVerifyAllocateCloseReceiptAndLadderUp() throws Exception {
        long loanId = loanIdOf(walkToActive(70L));
        String b = bearer("70", "BORROWER");

        // v1 BULLET schedule from the confirmed due date, immutable.
        var schedule = jdbc.queryForMap("select s.id, s.version, s.kind, s.status, i.seq, i.due_date, i.due_at,"
                + " i.principal_paise, i.interest_paise, l.due_date as loan_due, l.due_at as loan_due_at"
                + " from repayment_schedule s join schedule_instalment i on i.schedule_id = s.id"
                + " join loan l on l.id = s.loan_id where s.loan_id = ?", loanId);
        assertThat(schedule).containsEntry("version", 1).containsEntry("kind", "BULLET").containsEntry("status", "ACTIVE")
                .containsEntry("seq", 1).containsEntry("principal_paise", 100000L).containsEntry("interest_paise", 0L)
                .containsEntry("due_date", schedule.get("loan_due")).containsEntry("due_at", schedule.get("loan_due_at"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "update schedule_instalment set due_date = due_date + 14 where schedule_id = ?", schedule.get("id")))
                .hasMessageContaining("immutable");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "delete from repayment_schedule where id = ?", schedule.get("id")))
                .hasMessageContaining("cannot be deleted");

        // CC-22: the borrower needs a key; a replay with the same key returns the same payment.
        String part = repayment(40000, " utr pay 70a ", LocalDate.now(IST));
        mvc.perform(post("/api/loan/{id}/repayments", loanId).header("Authorization", b)
                        .contentType(MediaType.APPLICATION_JSON).content(part))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        long first = submit(loanId, b, "k-70-1", part);
        assertThat(submit(loanId, b, "k-70-1", part)).isEqualTo(first);
        mvc.perform(post("/api/loan/{id}/repayments", loanId).header("Authorization", b).header("Idempotency-Key", "k-70-1")
                        .contentType(MediaType.APPLICATION_JSON).content(repayment(50000, "UTR-PAY-70Z", LocalDate.now(IST))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from payment where loan_id = ?", Long.class, loanId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select txn_ref from payment where id = ?", String.class, first)).isEqualTo("UTRPAY70A");
        // CC-07: the same UTR again is refused.
        mvc.perform(post("/api/loan/{id}/repayments", loanId).header("Authorization", b).header("Idempotency-Key", "k-70-dup")
                        .contentType(MediaType.APPLICATION_JSON).content(repayment(40000, "UTRPAY70A", LocalDate.now(IST))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_UTR"));

        verifyPayment(loanId, first);
        assertThat(allocations(loanId)).containsExactly(java.util.Map.entry("PRINCIPAL", 40000L));
        assertThat(jdbc.queryForObject("select repayment_status from loan where id = ?", String.class, loanId)).isEqualTo("PARTIAL");

        long rest = submit(loanId, b, "k-70-2", repayment(60000, "UTR-PAY-70B", LocalDate.now(IST)));
        verifyPayment(loanId, rest);

        assertThat(allocations(loanId)).containsExactly(java.util.Map.entry("PRINCIPAL", 100000L));
        assertThat(jdbc.queryForMap("select lifecycle_status, repayment_status from loan where id = ?", loanId))
                .containsEntry("lifecycle_status", "CLOSED").containsEntry("repayment_status", "PAID");
        assertThat(jdbc.queryForObject("select i.status from schedule_instalment i join repayment_schedule s"
                + " on s.id = i.schedule_id where s.loan_id = ?", String.class, loanId)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select count(*) from loan_document where loan_id = ? and type = 'CLOSURE_RECEIPT'",
                Long.class, loanId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from refund_case where loan_id = ?", Long.class, loanId)).isZero();
        mvc.perform(get("/api/me/credit-level").header("Authorization", b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.level").value(2));
    }

    /**
     * R4 / CC-19: a loan 40 days late owes principal + the late charge capped at 30 days, never the prepaid
     * interest. CC-08: paying more than that closes the loan, splits penal → principal and opens a refund
     * case for the excess. CC-07: a UTR used on another loan is refused.
     */
    @Test
    void cc08_overpaymentOnALateLoanOpensARefundCaseAndTheLateChargeIsCapped() throws Exception {
        long loanId = loanIdOf(walkToActive(71L));
        LocalDate today = LocalDate.now(IST);
        LocalDate due = today.minusDays(40);
        jdbc.update("update loan set disbursed_on = ?, due_date = ?, due_at = ? where id = ?", due.minusDays(14), due,
                java.sql.Timestamp.from(due.atTime(23, 59, 59).atZone(IST).toInstant()), loanId);

        mvc.perform(get("/api/loan/{id}/outstanding", loanId).header("Authorization", bearer("71", "BORROWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outstandingPaise").value(160000))
                .andExpect(jsonPath("$.data.penaltyPaise").value(60000))
                .andExpect(jsonPath("$.data.penaltyDays").value(30))
                .andExpect(jsonPath("$.data.interestPaise").value(0))
                .andExpect(jsonPath("$.data.interestPrepaidPaise").value(14000));

        String admin = bearer("1", "ADMIN");
        long paid = submit("/api/loan/{id}/repayments/admin-record", loanId, admin, "k-71-1",
                repayment(170000, "UTR-PAY-71", today));
        verifyPayment(loanId, paid);

        assertThat(allocations(loanId)).containsOnly(java.util.Map.entry("PENAL", 60000L),
                java.util.Map.entry("PRINCIPAL", 100000L), java.util.Map.entry("EXCESS", 10000L));
        assertThat(jdbc.queryForMap("select amount_paise, reason, status, customer_id from refund_case where payment_id = ?", paid))
                .containsEntry("amount_paise", 10000L).containsEntry("reason", "OVERPAYMENT")
                .containsEntry("status", "OPEN").containsEntry("customer_id", 71L);
        mvc.perform(get("/api/refund-cases").param("status", "OPEN").header("Authorization", bearer("3", "ACCOUNTANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.paymentId==" + paid + ")].amountPaise").value(10000));
        mvc.perform(get("/api/refund-cases").header("Authorization", bearer("71", "BORROWER")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
        assertThat(jdbc.queryForObject("select lifecycle_status from loan where id = ?", String.class, loanId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select count(*) from loan_document where loan_id = ? and type = 'CLOSURE_RECEIPT'",
                Long.class, loanId)).isEqualTo(1L);
        // CC-07: another customer cannot reuse that UTR on their own loan.
        long other = loanIdOf(walkToActive(72L));
        mvc.perform(post("/api/loan/{id}/repayments", other).header("Authorization", bearer("72", "BORROWER"))
                        .header("Idempotency-Key", "k-72-1")
                        .contentType(MediaType.APPLICATION_JSON).content(repayment(100000, "utr-pay-71", today)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_UTR"));
        // Late: the level stays.
        mvc.perform(get("/api/me/credit-level").header("Authorization", bearer("71", "BORROWER")))
                .andExpect(jsonPath("$.data.level").value(1));
    }

    /** The gateway callback is refused while the payment-gateway flag is OFF (v1). */
    @Test
    void paymentWebhookIsRefusedWhileTheFlagIsOff() throws Exception {
        mvc.perform(post("/api/webhooks/payments/{provider}", "anygateway")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"event\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FEATURE_DISABLED"));
        assertThat(jdbc.queryForObject("select enabled from feature_flag where flag_key = 'payment-gateway'", Boolean.class))
                .isFalse();
    }

    /** With the flag ON and a provider adapter, a replayed callback records one payment, awaiting an Accountant. */
    @Test
    void paymentWebhookReplayIsIdempotent() throws Exception {
        long loanId = loanIdOf(walkToActive(73L));
        jdbc.update("update feature_flag set enabled = true where flag_key = 'payment-gateway'");
        try {
            String body = "{\"event\":\"evt-73\",\"loanId\":" + loanId + ",\"amount\":100000,\"utr\":\"GW-73\"}";
            long first = webhook(body);
            assertThat(webhook(body)).isEqualTo(first);
            assertThat(jdbc.queryForMap("select count(*) as n, max(status) as status from payment where loan_id = ?", loanId))
                    .containsEntry("n", 1L).containsEntry("status", "PENDING_VERIFICATION");
            mvc.perform(post("/api/webhooks/payments/{provider}", "nosuchgateway")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("UNKNOWN_PROVIDER"));
        } finally {
            jdbc.update("update feature_flag set enabled = false where flag_key = 'payment-gateway'");
        }
    }

    private long webhook(String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/webhooks/payments/{provider}", "testpay")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return data(r).asLong();
    }

    /** A provider adapter for the tests only; no gateway ships in v1. */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestGateway {
        @org.springframework.context.annotation.Bean
        com.navix.loan.service.PaymentGatewayPort testPayGateway(ObjectMapper om) {
            return new com.navix.loan.service.PaymentGatewayPort() {
                @Override
                public String provider() {
                    return "testpay";
                }

                @Override
                public GatewayPayment parse(java.util.Map<String, String> headers, String body) {
                    try {
                        JsonNode n = om.readTree(body);
                        return new GatewayPayment(n.get("event").asText(), n.get("loanId").asLong(),
                                n.get("amount").asLong(), n.get("utr").asText(), LocalDate.now(IST));
                    } catch (java.io.IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                }
            };
        }
    }

    private static String repayment(long paise, String utr, LocalDate paidOn) {
        return "{\"amountPaise\":" + paise + ",\"method\":\"UPI\",\"txnRef\":\"" + utr + "\",\"paidOn\":\"" + paidOn
                + "\",\"proofUrl\":\"loan/repayment-proof/shot.png\"}";
    }

    private long submit(long loanId, String bearer, String key, String body) throws Exception {
        return submit("/api/loan/{id}/repayments", loanId, bearer, key, body);
    }

    private long submit(String path, long loanId, String bearer, String key, String body) throws Exception {
        MvcResult r = mvc.perform(post(path, loanId)
                        .header("Authorization", bearer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_VERIFICATION"))
                .andReturn();
        return data(r).get("id").asLong();
    }

    private void verifyPayment(long loanId, long paymentId) throws Exception {
        mvc.perform(post("/api/loan/{id}/repayments/{pid}/verify", loanId, paymentId)
                        .header("Authorization", bearer("3", "ACCOUNTANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"));
    }

    private java.util.Map<String, Long> allocations(long loanId) {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        jdbc.query("select component, sum(amount_paise) from payment_allocation where loan_id = ? group by component"
                + " order by component", (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> out.put(rs.getString(1), rs.getLong(2)), loanId);
        return out;
    }

    // ---- PLAN Phase 6b: loan extension --------------------------------------------------

    private static final String HEAD_A = "7";
    private static final String HEAD_B = "8";

    /** A ₹1,000 loan 3 days past due, with the borrower's mobile on file. @return {appId, loanId} */
    private long[] overdueLoan(long customerId, int daysLate) throws Exception {
        long appId = walkToActive(customerId);
        long loanId = loanIdOf(appId);
        LocalDate due = LocalDate.now(IST).minusDays(daysLate);
        jdbc.update("update loan set disbursed_on = ?, due_date = ?, original_due_date = ?, due_at = ? where id = ?",
                due.minusDays(14), due, due, java.sql.Timestamp.from(due.atTime(23, 59, 59).atZone(IST).toInstant()),
                loanId);
        jdbc.update("insert into customer_profile (application_id, full_name, mobile, created_at, created_by)"
                + " values (?, 'Asha Rao', ?, now(), 'it')", appId, "98765" + String.format("%05d", customerId));
        return new long[] {appId, loanId};
    }

    private JsonNode extensionCall(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                   String bearer, String body) throws Exception {
        request = request.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.content(body);
        }
        return data(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    /** Offer (Head A, case auto-opened) → approve (Head B) → OTP → accept. @return the extension JSON */
    private JsonNode acceptedExtension(long loanId, long customerId) throws Exception {
        JsonNode offered = extensionCall(post("/api/collections/loans/{id}/extensions", loanId),
                bearer(HEAD_A, "COLLECTION_HEAD"), "{\"callNote\":\"Borrower asked for 14 days\"}");
        String extId = offered.get("id").asText();
        extensionCall(post("/api/collections/extensions/{id}/approve", extId), bearer(HEAD_B, "COLLECTION_HEAD"), null);
        String b = bearer(String.valueOf(customerId), "BORROWER");
        extensionCall(post("/api/loans/{l}/extensions/{e}/otp", loanId, extId), b, null);
        return extensionCall(post("/api/loans/{l}/extensions/{e}/accept", loanId, extId), b,
                "{\"otp\":\"123456\",\"consentVersion\":\"EXT-V1\"}");
    }

    private static String extensionPayment(long paise, String utr, String extId) {
        return "{\"amountPaise\":" + paise + ",\"method\":\"UPI\",\"txnRef\":\"" + utr + "\",\"paidOn\":\""
                + LocalDate.now(IST) + "\",\"proofUrl\":\"loan/repayment-proof/ext.png\",\"purpose\":\"EXTENSION\","
                + "\"extensionId\":\"" + extId + "\"}";
    }

    /**
     * The whole path: officer offers on a ₹1,000 loan 3 days late → a second offer is refused (409, and the DB
     * index) → the Head who offered cannot approve (SOD_VIOLATION) → another Head approves → the borrower sees it on
     * the loan, gets the OTP and accepts (idempotent) → pays ₹318 in the app (a wrong amount is refused) → nothing
     * moves until the Accountant verifies → due = old + 14, "Extended 1 of 3", principal untouched, schedule v2,
     * due history, balance_adjustment, addendum → repaying ₹1,000 closes the loan with no level change.
     */
    @Test
    void extension_offerApproveAcceptPayVerifyMovesTheDueDateAndClosesWithNoLevelUp() throws Exception {
        long[] ids = overdueLoan(90L, 3);
        long loanId = ids[1];
        LocalDate oldDue = LocalDate.now(IST).minusDays(3);
        String b = bearer("90", "BORROWER");

        JsonNode offered = extensionCall(post("/api/collections/loans/{id}/extensions", loanId),
                bearer(HEAD_A, "COLLECTION_HEAD"), "{\"callNote\":\"Borrower asked for 14 days\",\"recordingId\":\"REC-90\"}");
        String extId = offered.get("id").asText();
        String caseId = offered.get("caseId").asText();
        assertThat(offered.get("status").asText()).isEqualTo("OFFERED");
        assertThat(offered.get("newDueDate").asText()).isEqualTo(oldDue.plusDays(14).toString());

        // EXT-01: one open extension per loan, at the API and in the database.
        mvc.perform(post("/api/collections/cases/{id}/extensions", caseId)
                        .header("Authorization", bearer(HEAD_B, "COLLECTION_HEAD")).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXTENSION_ALREADY_OPEN"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("insert into loan_extension (id, loan_id,"
                        + " collection_case_id, seq, status, fee_paise, gst_paise, interest_paise, pay_by, expires_at,"
                        + " old_due_date, new_due_date, proposed_by, proposed_at) values (gen_random_uuid(), ?, cast(? as uuid),"
                        + " 99, 'OFFERED', 0, 0, 0, current_date, now(), current_date, current_date, 7, now())", loanId, caseId))
                .hasMessageContaining("uq_loan_extension_open");

        // The panel: today's quote is ₹318 (CC-15) and the cap is 3.
        mvc.perform(get("/api/collections/cases/{id}/extensions", caseId).header("Authorization", bearer(HEAD_B, "COLLECTION_HEAD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quoteForToday.totalPaise").value(31800))
                .andExpect(jsonPath("$.data.quoteForToday.lateChargePaise").value(6000))
                .andExpect(jsonPath("$.data.cap").value(3))
                .andExpect(jsonPath("$.data.used").value(0))
                .andExpect(jsonPath("$.data.reasonCode").value("EXTENSION_ALREADY_OPEN"))
                .andExpect(jsonPath("$.data.current.status").value("OFFERED"));

        // The borrower never sees an offer the Head has not approved.
        mvc.perform(get("/api/loan/{id}", loanId).header("Authorization", b))
                .andExpect(jsonPath("$.data.pendingExtension").doesNotExist());

        // EXT-04: the Head who offered cannot approve.
        expectSod(post("/api/collections/extensions/{id}/approve", extId), HEAD_A, "COLLECTION_HEAD", null);
        extensionCall(post("/api/collections/extensions/{id}/approve", extId), bearer(HEAD_B, "COLLECTION_HEAD"), null);

        mvc.perform(get("/api/loan/{id}", loanId).header("Authorization", b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingExtension.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.pendingExtension.quoteForToday.totalPaise").value(31800))
                .andExpect(jsonPath("$.data.pendingExtension.cap").value(3))
                .andExpect(jsonPath("$.data.pendingExtension.newDueDate").value(oldDue.plusDays(14).toString()))
                .andExpect(jsonPath("$.data.extensionCount").value(0));
        extensionCall(post("/api/loans/{l}/extensions/{e}/seen", loanId, extId), b, null);
        assertThat(jdbc.queryForObject("select seen_by_borrower_at is not null from loan_extension where id = cast(? as uuid)",
                Boolean.class, extId)).isTrue();

        // Another borrower cannot touch it.
        mvc.perform(post("/api/loans/{l}/extensions/{e}/seen", loanId, extId).header("Authorization", bearer("91", "BORROWER")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_OWNER"));

        // OTP confirm; EXT-12: a retry is idempotent.
        extensionCall(post("/api/loans/{l}/extensions/{e}/otp", loanId, extId), b, null);
        mvc.perform(post("/api/loans/{l}/extensions/{e}/accept", loanId, extId).header("Authorization", b)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"otp\":\"000000\",\"consentVersion\":\"EXT-V1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_OTP"));
        JsonNode accepted = extensionCall(post("/api/loans/{l}/extensions/{e}/accept", loanId, extId), b,
                "{\"otp\":\"123456\",\"consentVersion\":\"EXT-V1\"}");
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(extensionCall(post("/api/loans/{l}/extensions/{e}/accept", loanId, extId), b, "{}")
                .get("status").asText()).isEqualTo("ACCEPTED");

        // EXT-16: a short payment is refused; the exact quote is recorded and waits for the Accountant.
        mvc.perform(post("/api/loan/{id}/repayments", loanId).header("Authorization", b).header("Idempotency-Key", "k-90-x")
                        .contentType(MediaType.APPLICATION_JSON).content(extensionPayment(25800, "UTR-EXT-90A", extId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EXTENSION_AMOUNT_MISMATCH"));
        long paid = submit(loanId, b, "k-90-1", extensionPayment(31800, "UTR-EXT-90", extId));
        assertThat(jdbc.queryForObject("select status from loan_extension where id = cast(? as uuid)", String.class, extId))
                .isEqualTo("PAYMENT_PENDING");
        // Only the Accountant's verification moves the due date.
        assertThat(jdbc.queryForObject("select due_date from loan where id = ?", LocalDate.class, loanId)).isEqualTo(oldDue);

        verifyPayment(loanId, paid);

        var loan = jdbc.queryForMap("select due_date, original_due_date, extension_count, lifecycle_status, status,"
                + " penal_days_used from loan where id = ?", loanId);
        assertThat(loan).containsEntry("extension_count", 1).containsEntry("lifecycle_status", "ACTIVE")
                .containsEntry("status", "ACTIVE").containsEntry("penal_days_used", 3);
        assertThat(((java.sql.Date) loan.get("due_date")).toLocalDate()).isEqualTo(oldDue.plusDays(14));
        assertThat(((java.sql.Date) loan.get("original_due_date")).toLocalDate()).isEqualTo(oldDue);
        assertThat(jdbc.queryForObject("select status from loan_extension where id = cast(? as uuid)", String.class, extId))
                .isEqualTo("ACTIVE");
        // The charge is split into its parts; principal is untouched (no PRINCIPAL row, outstanding still ₹1,000).
        assertThat(allocations(loanId)).containsOnly(java.util.Map.entry("EXT_FEE", 10000L),
                java.util.Map.entry("EXT_GST", 1800L), java.util.Map.entry("EXT_INTEREST", 14000L),
                java.util.Map.entry("PENAL", 6000L));
        mvc.perform(get("/api/loan/{id}/outstanding", loanId).header("Authorization", b))
                .andExpect(jsonPath("$.data.outstandingPaise").value(100000))
                .andExpect(jsonPath("$.data.verifiedPaise").value(0))
                .andExpect(jsonPath("$.data.penaltyPaise").value(0));
        assertThat(jdbc.queryForList("select version, kind, status from repayment_schedule where loan_id = ? order by version",
                loanId)).extracting(r -> r.get("kind") + ":" + r.get("status"))
                .containsExactly("BULLET:SUPERSEDED", "EXTENSION:ACTIVE");
        assertThat(jdbc.queryForObject("select i.principal_paise from schedule_instalment i join repayment_schedule s"
                + " on s.id = i.schedule_id where s.loan_id = ? and s.status = 'ACTIVE'", Long.class, loanId)).isEqualTo(100000L);
        assertThat(jdbc.queryForList("select seq, penal_days, to_at is null as open from loan_due_history where loan_id = ?"
                + " order by seq", loanId)).extracting(r -> r.get("seq") + ":" + r.get("penal_days") + ":" + r.get("open"))
                .containsExactly("1:3:false", "2:0:true");
        assertThat(jdbc.queryForObject("select count(*) from balance_adjustment where loan_id = ? and kind = 'EXTENSION'",
                Long.class, loanId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from loan_document where loan_id = ? and type = 'EXTENSION_ADDENDUM'",
                Long.class, loanId)).isEqualTo(1L);
        mvc.perform(get("/api/loan/{id}", loanId).header("Authorization", b))
                .andExpect(jsonPath("$.data.extensionCount").value(1))
                .andExpect(jsonPath("$.data.originalDueDate").value(oldDue.toString()))
                .andExpect(jsonPath("$.data.pendingExtension").doesNotExist())
                .andExpect(jsonPath("$.data.extensions[0].status").value("ACTIVE"));

        // Item 11: the extended loan stays on the worklist although its new due date is past the 7-day horizon.
        mvc.perform(get("/api/collections/worklist").header("Authorization", bearer(HEAD_B, "COLLECTION_HEAD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.loanId==" + loanId + ")].extState").value("ACTIVE"))
                .andExpect(jsonPath("$.data[?(@.loanId==" + loanId + ")].extensionCount").value(1));

        // Repaying the principal closes the loan; an extended loan never raises the level.
        long repaid = submit(loanId, b, "k-90-2", repayment(100000, "UTR-PAY-90", LocalDate.now(IST)));
        verifyPayment(loanId, repaid);
        assertThat(jdbc.queryForObject("select lifecycle_status from loan where id = ?", String.class, loanId)).isEqualTo("CLOSED");
        assertThat(allocations(loanId)).containsEntry("PRINCIPAL", 100000L).doesNotContainKey("EXCESS");
        mvc.perform(get("/api/me/credit-level").header("Authorization", b)).andExpect(jsonPath("$.data.level").value(1));
        assertThat(jdbc.queryForObject("select reason from credit_level_history where loan_id = ? and reason <> 'FIRST_LOAN'",
                String.class, loanId)).isEqualTo("EXTENDED_NO_CHANGE");
    }

    /**
     * EXT-20 / EXT-15: the officer logs the extension payment; two Accountants validate it at the same moment and
     * the due date moves once. The same UTR cannot then be used for a repayment.
     */
    @Test
    void ext20_twoAccountantsValidatingAtOnceMoveTheDueDateOnce() throws Exception {
        long[] ids = overdueLoan(92L, 3);
        long loanId = ids[1];
        JsonNode ext = acceptedExtension(loanId, 92L);
        String caseId = jdbc.queryForObject(
                "select cast(collection_case_id as text) from loan_extension where id = cast(? as uuid)", String.class,
                ext.get("id").asText());
        JsonNode raised = extensionCall(post("/api/collections/cases/{id}/payments", caseId), bearer(HEAD_A, "COLLECTION_HEAD"),
                "{\"kind\":\"EXTENSION\",\"amountPaise\":31800,\"txnRef\":\"UTR-EXT-92\",\"extensionId\":\""
                        + ext.get("id").asText() + "\"}");
        String paymentId = raised.get("id").asText();

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (String accountant : new String[] {"3", "4"}) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/collections/payments/{id}/validate", paymentId)
                                .header("Authorization", bearer(accountant, "ACCOUNTANT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{\"accept\":true}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        java.util.List<Integer> codes = new java.util.ArrayList<>();
        for (var f : results) {
            codes.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(codes).containsOnlyOnce(200);
        assertThat(jdbc.queryForMap("select extension_count, due_date from loan where id = ?", loanId))
                .containsEntry("extension_count", 1)
                .containsEntry("due_date", java.sql.Date.valueOf(LocalDate.now(IST).minusDays(3).plusDays(14)));
        assertThat(jdbc.queryForObject("select count(*) from payment where loan_id = ? and purpose = 'EXTENSION'",
                Long.class, loanId)).isEqualTo(1L);
        // EXT-15: the extension's UTR cannot be reused for a repayment.
        mvc.perform(post("/api/loan/{id}/repayments", loanId).header("Authorization", bearer("92", "BORROWER"))
                        .header("Idempotency-Key", "k-92-1").contentType(MediaType.APPLICATION_JSON)
                        .content(repayment(100000, "UTR-EXT-92", LocalDate.now(IST))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UTR_USED_FOR_OTHER_PURPOSE"));
    }

    /** EXT-25: a 4th extension without an ADMIN override is 422 EXTENSION_CAP_REACHED; ADMIN with a reason may. */
    @Test
    void ext25_aFourthExtensionNeedsAnAdminOverride() throws Exception {
        long[] ids = overdueLoan(93L, 1);
        long loanId = ids[1];
        jdbc.update("update loan set extension_count = 3 where id = ?", loanId);
        mvc.perform(post("/api/collections/loans/{id}/extensions", loanId)
                        .header("Authorization", bearer(HEAD_A, "COLLECTION_HEAD")).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EXTENSION_CAP_REACHED"));
        JsonNode overridden = extensionCall(post("/api/collections/loans/{id}/extensions", loanId), bearer("1", "ADMIN"),
                "{\"overrideReason\":\"Hospitalised, approved by credit head\"}");
        assertThat(overridden.get("overrideReason").asText()).isEqualTo("Hospitalised, approved by credit head");
    }

    /** CC-67 / EXT-29: an accepted offer never paid expires in the 00:05 sweep; nothing on the loan changes. */
    @Test
    void cc67_anAcceptedOfferThatIsNeverPaidExpires() throws Exception {
        long[] ids = overdueLoan(94L, 2);
        long loanId = ids[1];
        JsonNode ext = acceptedExtension(loanId, 94L);
        jdbc.update("update loan_extension set expires_at = now() - interval '1 minute' where id = cast(? as uuid)",
                ext.get("id").asText());
        assertThat(sweep.expireAll()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select status from loan_extension where id = cast(? as uuid)", String.class,
                ext.get("id").asText())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select extension_count from loan where id = ?", Integer.class, loanId)).isZero();
        mvc.perform(get("/api/loan/{id}", loanId).header("Authorization", bearer("94", "BORROWER")))
                .andExpect(jsonPath("$.data.pendingExtension").doesNotExist())
                .andExpect(jsonPath("$.data.extensions[0].status").value("EXPIRED"));
    }

    @Autowired
    private com.navix.scheduler.ExtensionExpirySweep sweep;

    // ---- PLAN Phase 6c: recovery EMI, reversal, closure type, refunds --------------------

    private static final String ADMIN_A = "1";
    private static final String ADMIN_B = "2";

    /**
     * The whole EMI path on a ₹7,000 loan 3 days late (owed ₹7,420): flag OFF refused → request (Head) → a second
     * request 409 → an extension refused while it is open → a Head cannot approve (only ADMIN) → the requester
     * cannot approve (SoD) → an ADMIN approves (RESTRUCTURE_PENDING) → the borrower sees three instalments → OTP
     * accept (wrong code refused, replay idempotent) → RESTRUCTURED, EMI schedule v2 (₹2,333.33 ×2 + ₹2,333.34 of
     * principal, ₹140 late charge each), balance_adjustment, no late charge after → instalment 1 paid → the rest
     * paid closes the loan (REPAID, level unchanged).
     */
    @Test
    void emi_requestApproveAcceptSplitsWhatIsOwedAndCloses() throws Exception {
        long[] ids = overdueLoan(120L, 3);
        long loanId = ids[1];
        jdbc.update("update loan set principal = 700000, total_repayable = 700000 where id = ?", loanId);
        String officer = bearer(HEAD_A, "COLLECTION_HEAD");
        String b = bearer("120", "BORROWER");

        expectCode(post("/api/collections/loans/{id}/emi", loanId).header("Authorization", officer)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), "FEATURE_DISABLED");
        jdbc.update("update feature_flag set enabled = true where flag_key = 'emi-conversion'");
        try {
            mvc.perform(get("/api/collections/loans/{id}/emi", loanId).header("Authorization", officer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.eligible").value(true))
                    .andExpect(jsonPath("$.data.owedTodayPaise").value(742000))
                    .andExpect(jsonPath("$.data.previewForToday.length()").value(3));

            JsonNode requested = extensionCall(post("/api/collections/loans/{id}/emi", loanId), officer,
                    "{\"note\":\"Lost job, can pay in three parts\"}");
            String emiId = requested.get("id").asText();
            assertThat(requested.get("status").asText()).isEqualTo("REQUESTED");
            assertThat(requested.get("instalmentCount").asInt()).isEqualTo(3);
            mvc.perform(post("/api/collections/loans/{id}/emi", loanId).header("Authorization", officer)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("EMI_ALREADY_OPEN"));
            // No extension while a conversion is open.
            expectCode(post("/api/collections/loans/{id}/extensions", loanId).header("Authorization", bearer(HEAD_B,
                    "COLLECTION_HEAD")).contentType(MediaType.APPLICATION_JSON).content("{}"), "EMI_OPEN");

            // Only an ADMIN approves, and never the requester.
            expectCode(post("/api/collections/emi/{id}/approve", emiId).header("Authorization",
                    bearer(HEAD_B, "COLLECTION_HEAD")), "FORBIDDEN_ROLE");
            expectSod(post("/api/collections/emi/{id}/approve", emiId), HEAD_A, "ADMIN", null);
            mvc.perform(get("/api/collections/emi").header("Authorization", bearer(ADMIN_A, "ADMIN")))
                    .andExpect(jsonPath("$.data[?(@.id=='" + emiId + "')].status").value("REQUESTED"));
            JsonNode approved = extensionCall(post("/api/collections/emi/{id}/approve", emiId),
                    bearer(ADMIN_A, "ADMIN"), null);
            assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("select lifecycle_status from loan where id = ?", String.class, loanId))
                    .isEqualTo("RESTRUCTURE_PENDING");

            JsonNode offer = extensionCall(get("/api/loans/{id}/emi", loanId), b, null);
            assertThat(offer.get("status").asText()).isEqualTo("APPROVED");
            assertThat(offer.get("totalPaise").asLong()).isEqualTo(742000L);
            assertThat(offer.get("instalments").get(2).get("principalPaise").asLong()).isEqualTo(233334L);
            mvc.perform(get("/api/loans/{id}/emi", loanId).header("Authorization", bearer("121", "BORROWER")))
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN_OWNER"));

            extensionCall(post("/api/loans/{l}/emi/{e}/otp", loanId, emiId), b, null);
            expectCode(post("/api/loans/{l}/emi/{e}/accept", loanId, emiId).header("Authorization", b)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"otp\":\"000000\",\"consentVersion\":\"EMI-V1\"}"), "INVALID_OTP");
            String accept = "{\"otp\":\"123456\",\"consentVersion\":\"EMI-V1\",\"version\":" + offer.get("version") + "}";
            JsonNode active = extensionCall(post("/api/loans/{l}/emi/{e}/accept", loanId, emiId), b, accept);
            assertThat(active.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(extensionCall(post("/api/loans/{l}/emi/{e}/accept", loanId, emiId), b, accept)
                    .get("status").asText()).isEqualTo("ACTIVE");
            assertThat(active.get("instalments").get(0).get("dueDate").asText())
                    .isEqualTo(LocalDate.now(IST).plusDays(10).toString());

            assertThat(jdbc.queryForObject("select lifecycle_status from loan where id = ?", String.class, loanId))
                    .isEqualTo("RESTRUCTURED");
            assertThat(jdbc.queryForList("select version, kind, status from repayment_schedule where loan_id = ?"
                    + " order by version", loanId)).extracting(r -> r.get("kind") + ":" + r.get("status"))
                    .containsExactly("BULLET:SUPERSEDED", "EMI:ACTIVE");
            assertThat(jdbc.queryForList("select i.principal_paise, i.penal_paise from schedule_instalment i join"
                    + " repayment_schedule s on s.id = i.schedule_id where s.loan_id = ? and s.kind = 'EMI' order by i.seq",
                    loanId)).extracting(r -> r.get("principal_paise") + "+" + r.get("penal_paise"))
                    .containsExactly("233333+14000", "233333+14000", "233334+14000");
            assertThat(jdbc.queryForObject("select count(*) from balance_adjustment where loan_id = ? and kind = 'EMI'",
                    Long.class, loanId)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select count(*) from idempotency_key where key = ?", Long.class,
                    "emi:" + emiId)).isEqualTo(1L);
            // emi.penal off: moving the old due date further back adds no late charge.
            jdbc.update("update loan set due_date = due_date - 5 where id = ?", loanId);
            mvc.perform(get("/api/loan/{id}/outstanding", loanId).header("Authorization", b))
                    .andExpect(jsonPath("$.data.outstandingPaise").value(742000));

            String admin = bearer(ADMIN_A, "ADMIN");
            long first = submit("/api/loan/{id}/repayments/admin-record", loanId, admin, "k-120-1",
                    repayment(247333, "UTR-EMI-120-1", LocalDate.now(IST)));
            verifyPayment(loanId, first);
            assertThat(jdbc.queryForList("select i.status from schedule_instalment i join repayment_schedule s"
                    + " on s.id = i.schedule_id where s.loan_id = ? and s.kind = 'EMI' order by i.seq", String.class, loanId))
                    .containsExactly("PAID", "DUE", "DUE");
            long rest = submit("/api/loan/{id}/repayments/admin-record", loanId, admin, "k-120-2",
                    repayment(494667, "UTR-EMI-120-2", LocalDate.now(IST)));
            verifyPayment(loanId, rest);
            assertThat(jdbc.queryForMap("select lifecycle_status, closure_type from loan where id = ?", loanId))
                    .containsEntry("lifecycle_status", "CLOSED").containsEntry("closure_type", "REPAID");
            assertThat(jdbc.queryForObject("select count(*) from schedule_instalment i join repayment_schedule s"
                    + " on s.id = i.schedule_id where s.loan_id = ? and s.kind = 'EMI' and i.status = 'PAID'", Long.class,
                    loanId)).isEqualTo(3L);
            mvc.perform(get("/api/me/credit-level").header("Authorization", b))
                    .andExpect(jsonPath("$.data.level").value(1));
        } finally {
            jdbc.update("update feature_flag set enabled = false where flag_key = 'emi-conversion'");
        }
    }

    /** A declined or expired EMI offer puts the loan back to overdue. */
    @Test
    void emi_aDeclinedOfferPutsTheLoanBackToOverdue() throws Exception {
        long loanId = overdueLoan(122L, 2)[1];
        jdbc.update("update feature_flag set enabled = true where flag_key = 'emi-conversion'");
        try {
            String emiId = extensionCall(post("/api/collections/loans/{id}/emi", loanId),
                    bearer(HEAD_A, "COLLECTION_HEAD"), "{}").get("id").asText();
            extensionCall(post("/api/collections/emi/{id}/approve", emiId), bearer(ADMIN_A, "ADMIN"), null);
            JsonNode declined = extensionCall(post("/api/loans/{l}/emi/{e}/decline", loanId, emiId),
                    bearer("122", "BORROWER"), "{\"reason\":\"NOT_NOW\"}");
            assertThat(declined.get("status").asText()).isEqualTo("DECLINED");
            assertThat(jdbc.queryForObject("select lifecycle_status from loan where id = ?", String.class, loanId))
                    .isEqualTo("OVERDUE_D1");

            String again = extensionCall(post("/api/collections/loans/{id}/emi", loanId),
                    bearer(HEAD_A, "COLLECTION_HEAD"), "{}").get("id").asText();
            extensionCall(post("/api/collections/emi/{id}/approve", again), bearer(ADMIN_B, "ADMIN"), null);
            jdbc.update("update emi_conversion set expires_at = now() - interval '1 minute' where id = cast(? as uuid)",
                    again);
            assertThat(sweep.expireEmiOffers()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.queryForObject("select status from emi_conversion where id = cast(? as uuid)", String.class,
                    again)).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("select lifecycle_status from loan where id = ?", String.class, loanId))
                    .isEqualTo("OVERDUE_D1");
        } finally {
            jdbc.update("update feature_flag set enabled = false where flag_key = 'emi-conversion'");
        }
    }

    /**
     * CC-10: the payment that closed a loan on time bounced. Its verifier cannot reverse it; another ADMIN does:
     * the payment is REVERSED, its split and instalment payment undone, the loan and application open again, the
     * level the closure gave is taken back, all audited. Paying again closes it and moves the level up again.
     */
    @Test
    void cc10_reversingTheClosingPaymentReopensTheLoanAndTakesBackTheLevel() throws Exception {
        long appId = walkToActive(123L);
        long loanId = loanIdOf(appId);
        LocalDate today = LocalDate.now(IST);
        long paid = submit("/api/loan/{id}/repayments/admin-record", loanId, bearer(ADMIN_A, "ADMIN"), "k-123-1",
                repayment(100000, "UTR-BOUNCE-123", today));
        verifyPayment(loanId, paid);
        String b = bearer("123", "BORROWER");
        mvc.perform(get("/api/me/credit-level").header("Authorization", b)).andExpect(jsonPath("$.data.level").value(2));
        assertThat(jdbc.queryForObject("select closure_type from loan where id = ?", String.class, loanId)).isEqualTo("REPAID");

        String reverse = "{\"reason\":\"Bank returned the transfer\"}";
        expectSod(post("/api/loan/{l}/repayments/{p}/reverse", loanId, paid), "3", "ACCOUNTANT", reverse);
        expectSod(post("/api/loan/{l}/repayments/{p}/reverse", loanId, paid), ADMIN_A, "ADMIN", reverse);
        JsonNode reversed = extensionCall(post("/api/loan/{l}/repayments/{p}/reverse", loanId, paid),
                bearer(ADMIN_B, "ADMIN"), reverse);
        assertThat(reversed.get("status").asText()).isEqualTo("REVERSED");

        assertThat(jdbc.queryForMap("select lifecycle_status, closed_on, closure_type, outstanding from loan where id = ?",
                loanId)).containsEntry("lifecycle_status", "ACTIVE").containsEntry("closed_on", null)
                .containsEntry("closure_type", null).containsEntry("outstanding", 100000L);
        assertStatusEquals(appId, "ACTIVE");
        assertThat(allocations(loanId)).isEmpty();
        assertThat(jdbc.queryForObject("select i.paid_paise from schedule_instalment i join repayment_schedule s"
                + " on s.id = i.schedule_id where s.loan_id = ? and s.status = 'ACTIVE'", Long.class, loanId)).isZero();
        mvc.perform(get("/api/me/credit-level").header("Authorization", b)).andExpect(jsonPath("$.data.level").value(1));
        assertThat(jdbc.queryForObject("select count(*) from credit_level_history where loan_id = ? and reason = 'CLOSURE_REVERSED'",
                Long.class, loanId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from balance_adjustment where loan_id = ? and kind = 'PAYMENT_REVERSAL'",
                Long.class, loanId)).isEqualTo(1L);
        expectCode(post("/api/loan/{l}/repayments/{p}/reverse", loanId, paid).header("Authorization",
                bearer(ADMIN_B, "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(reverse), "PAYMENT_NOT_VERIFIED");

        long again = submit("/api/loan/{id}/repayments/admin-record", loanId, bearer(ADMIN_A, "ADMIN"), "k-123-2",
                repayment(100000, "UTR-GOOD-123", today));
        verifyPayment(loanId, again);
        assertThat(jdbc.queryForObject("select lifecycle_status from loan where id = ?", String.class, loanId)).isEqualTo("CLOSED");
        mvc.perform(get("/api/me/credit-level").header("Authorization", b)).andExpect(jsonPath("$.data.level").value(2));
    }

    /** CC-05: paid on the due date, verified the day after while overdue: closed on time, the overdue reversed. */
    @Test
    void cc05_paidOnTheDueDateVerifiedLateIsOnTime() throws Exception {
        long loanId = overdueLoan(124L, 1)[1];
        jdbc.update("update loan set lifecycle_status = 'OVERDUE_D1' where id = ?", loanId);
        LocalDate due = LocalDate.now(IST).minusDays(1);
        long paid = submit("/api/loan/{id}/repayments/admin-record", loanId, bearer(ADMIN_A, "ADMIN"), "k-124-1",
                repayment(100000, "UTR-ONTIME-124", due));
        verifyPayment(loanId, paid);
        assertThat(jdbc.queryForObject("select lifecycle_status from loan where id = ?", String.class, loanId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select count(*) from balance_adjustment where loan_id = ? and kind = 'LATE_REVERSED'",
                Long.class, loanId)).isEqualTo(1L);
        mvc.perform(get("/api/me/credit-level").header("Authorization", bearer("124", "BORROWER")))
                .andExpect(jsonPath("$.data.level").value(2));
    }

    /** CC-17 and the refund case actions: an instalment past due reads OVERDUE; a refund is paid back with a UTR once. */
    @Test
    void cc17_overdueInstalmentAndRefundCaseActions() throws Exception {
        long loanId = loanIdOf(walkToActive(125L));
        assertThat(schedules.markOverdue(java.time.Instant.now().plus(java.time.Duration.ofDays(15)))).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select i.status from schedule_instalment i join repayment_schedule s"
                + " on s.id = i.schedule_id where s.loan_id = ?", String.class, loanId)).isEqualTo("OVERDUE");

        long paid = submit("/api/loan/{id}/repayments/admin-record", loanId, bearer(ADMIN_A, "ADMIN"), "k-125-1",
                repayment(100500, "UTR-OVER-125", LocalDate.now(IST)));
        verifyPayment(loanId, paid);
        assertThat(jdbc.queryForObject("select i.status from schedule_instalment i join repayment_schedule s"
                + " on s.id = i.schedule_id where s.loan_id = ?", String.class, loanId)).isEqualTo("PAID");
        long refundId = jdbc.queryForObject("select id from refund_case where payment_id = ?", Long.class, paid);

        String accountant = bearer("3", "ACCOUNTANT");
        expectCode(post("/api/refund-cases/{id}/refunded", refundId).header("Authorization", accountant)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), "UTR_REQUIRED");
        JsonNode refunded = extensionCall(post("/api/refund-cases/{id}/refunded", refundId), accountant,
                "{\"utr\":\"rf 125\"}");
        assertThat(refunded.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(refunded.get("refundUtr").asText()).isEqualTo("RF125");
        mvc.perform(post("/api/refund-cases/{id}/cancel", refundId).header("Authorization", accountant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Duplicate\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STALE_STATE"));
        expectCode(post("/api/refund-cases/{id}/cancel", refundId).header("Authorization", bearer("125", "BORROWER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"), "FORBIDDEN_ROLE");
        // A payment whose excess was refunded cannot be reversed until the refund is recovered.
        expectCode(post("/api/loan/{l}/repayments/{p}/reverse", loanId, paid).header("Authorization",
                bearer(ADMIN_B, "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Bounced\"}"), "REFUND_ALREADY_PAID");
    }

    @Autowired
    private com.navix.loan.service.RepaymentScheduleService schedules;

    // ---- PLAN Phase 5: documents ------------------------------------------------------

    private static final String ESIGN_INIT =
            "{\"successRedirectUrl\":\"https://dhannow.com/app/cb/esign?r=ok\",\"failureRedirectUrl\":\"https://dhannow.com/app/cb/esign?r=no\"}";

    /**
     * KFS → acknowledge → e-sign → the signed pack is the AGREEMENT; after the Accountant confirms, the
     * SCHEDULE is issued, and the loan's documents are listed for the owner only, with 5-minute links.
     */
    @Test
    void documents_kfsAckGatesESignAndEveryDocumentIsOnTheLoan() throws Exception {
        long appId = sanctioned(60L);
        String b = bearer("60", "BORROWER");

        expectCode(post("/api/applications/{id}/offer/esign/init", appId).header("Authorization", b)
                .contentType(MediaType.APPLICATION_JSON).content(ESIGN_INIT), "KFS_NOT_ACKNOWLEDGED");
        expectCode(post("/api/applications/{id}/offer/kfs/ack", appId).header("Authorization", b), "KFS_NOT_ISSUED");

        mvc.perform(post("/api/applications/{id}/offer/kfs", appId).header("Authorization", b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.acknowledged").value(false))
                .andExpect(jsonPath("$.data.url").value("https://s3.test/applications/" + appId + "/documents/kfs-v1.pdf"));
        mvc.perform(post("/api/applications/{id}/offer/kfs/ack", appId).header("Authorization", b)
                        .header("X-Forwarded-For", "198.51.100.7, 203.0.113.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acknowledged").value(true));
        assertThat(jdbc.queryForMap("select accepted_ip, pricing_version_id, amount_paise, length(sha256) as hash"
                        + " from loan_document where application_id = ? and type = 'KFS'", appId))
                .containsEntry("accepted_ip", "203.0.113.9").containsEntry("pricing_version_id", 1L)
                .containsEntry("amount_paise", 100000L).containsEntry("hash", 64);

        mvc.perform(post("/api/applications/{id}/offer/esign/init", appId).header("Authorization", b)
                .contentType(MediaType.APPLICATION_JSON).content(ESIGN_INIT)).andExpect(status().isOk());
        mvc.perform(post("/api/applications/{id}/offer/sanction-letter", appId).header("Authorization", b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://s3.test/applications/" + appId
                        + "/sanction_letter/sanction-letter.pdf"));
        when(verificationService.recordManualEsign(org.mockito.ArgumentMatchers.eq(appId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ApplicationVerificationService.StepResult(ApplicationVerificationService.ESIGN,
                        ApplicationVerificationService.PASS, "signed",
                        java.util.Map.of("signedAt", "2026-11-01T07:47:18Z")));
        mvc.perform(post("/api/applications/{id}/offer/esign", appId).header("Authorization", b)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signatureDataUrl\":\"data:image/png;base64," + signaturePngBase64() + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForMap("select s3_key, accepted_at is not null as accepted, acceptance_evidence::text as ev"
                        + " from loan_document where application_id = ? and type = 'AGREEMENT'", appId))
                .containsEntry("s3_key", "applications/" + appId + "/signed_agreement/sanction-letter-signed.pdf")
                .containsEntry("accepted", true)
                .hasEntrySatisfying("ev", ev -> assertThat((String) ev).contains("DRAWN_SIGNATURE"));

        recordEsign(appId); // the ESIGN row the mocked verification service would have written
        act(appId, "accept-offer", "60", "BORROWER", "{\"amountPaise\":100000}", "DISBURSEMENT_PENDING");
        act(appId, "disbursement-decision", "6", "DISBURSEMENT_HEAD",
                "{\"decision\":true,\"txnRef\":\"UTR-DOC-60\"}", "DISBURSAL_CONFIRMATION_PENDING");
        confirm(appId, "3", "ACCOUNTANT", "ACTIVE");
        long loanId = loanIdOf(appId);

        MvcResult list = mvc.perform(get("/api/loans/{id}/documents", loanId).header("Authorization", b))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(data(list).findValuesAsText("type")).containsExactly("KFS", "SANCTION", "AGREEMENT", "SCHEDULE");
        long scheduleId = data(list).get(3).get("id").asLong();
        assertThat(jdbc.queryForObject("select loan_id from loan_document where id = ?", Long.class, scheduleId))
                .isEqualTo(loanId);
        mvc.perform(get("/api/loans/{id}/documents/{doc}/url", loanId, scheduleId).header("Authorization", b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300));
        org.mockito.Mockito.verify(storage).presignDownload(
                "applications/" + appId + "/documents/schedule-v1.pdf", java.time.Duration.ofMinutes(5));
        assertThat(jdbc.queryForObject("select count(*) from loan_document_access where loan_document_id = ?",
                Long.class, scheduleId)).isZero();

        // Another borrower: 422 FORBIDDEN_OWNER on the list and on the link.
        expectCode(get("/api/loans/{id}/documents", loanId).header("Authorization", bearer("61", "BORROWER")),
                "FORBIDDEN_OWNER");
        expectCode(get("/api/loans/{id}/documents/{doc}/url", loanId, scheduleId)
                .header("Authorization", bearer("61", "BORROWER")), "FORBIDDEN_OWNER");

        // Staff may read it, and the read is audited.
        mvc.perform(get("/api/loans/{id}/documents/{doc}/url", loanId, scheduleId)
                        .header("Authorization", bearer("3", "ACCOUNTANT")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForMap("select actor_id, actor_role from loan_document_access where loan_document_id = ?",
                scheduleId)).containsEntry("actor_id", "3").containsEntry("actor_role", "ACCOUNTANT");
    }

    /** CC-02: a lapsed offer cannot be signed on its old KFS; the next KFS re-quotes and must be acknowledged. */
    @Test
    void cc02_aLapsedOfferIsReQuotedAndItsOldKfsCanNeverBeSigned() throws Exception {
        long appId = sanctioned(62L);
        String b = bearer("62", "BORROWER");
        assertThat(jdbc.queryForObject("select offer_valid_until > now() + interval '71 hours'"
                + " and offer_valid_until <= now() + interval '72 hours' from loan_application where id = ?",
                Boolean.class, appId)).isTrue();
        mvc.perform(post("/api/applications/{id}/offer/kfs", appId).header("Authorization", b)).andExpect(status().isOk());
        mvc.perform(post("/api/applications/{id}/offer/kfs/ack", appId).header("Authorization", b)).andExpect(status().isOk());

        jdbc.update("update loan_application set offer_valid_until = now() - interval '1 minute' where id = ?", appId);

        expectCode(post("/api/applications/{id}/offer/esign/init", appId).header("Authorization", b)
                .contentType(MediaType.APPLICATION_JSON).content(ESIGN_INIT), "OFFER_EXPIRED");
        mvc.perform(post("/api/applications/{id}/offer/kfs", appId).header("Authorization", b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.acknowledged").value(false));
        expectCode(post("/api/applications/{id}/offer/esign/init", appId).header("Authorization", b)
                .contentType(MediaType.APPLICATION_JSON).content(ESIGN_INIT), "KFS_NOT_ACKNOWLEDGED");
        mvc.perform(post("/api/applications/{id}/offer/kfs/ack", appId).header("Authorization", b)).andExpect(status().isOk());
        mvc.perform(post("/api/applications/{id}/offer/esign/init", appId).header("Authorization", b)
                .contentType(MediaType.APPLICATION_JSON).content(ESIGN_INIT)).andExpect(status().isOk());
    }

    /** V100: a document belongs to an application or a loan; offers carry a validity (3 days seeded). */
    @Test
    void v100_aLoanDocumentBelongsToAnApplicationOrALoan() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("insert into loan_document (type) values ('KFS')"))
                .hasMessageContaining("loan_document_owner_check");
        assertThat(jdbc.queryForObject("select offer_validity_days from product_config where version = 1",
                Integer.class)).isEqualTo(3);
    }

    /** A first loan (₹1,000) sanctioned and waiting on the borrower's offer journey. */
    private long sanctioned(long customerId) throws Exception {
        long appId = createApplication(customerId);
        String c = String.valueOf(customerId);
        act(appId, "submit-kyc", c, "BORROWER", null, "KYC_PENDING");
        act(appId, "assign", "5", "CREDIT_HEAD", "{\"executiveId\":2}", "CREDIT_EXEC_PENDING");
        act(appId, "sanction", "2", "CREDIT_EXECUTIVE",
                "{\"sanctionedAmountPaise\":100000,\"salaryCreditDay\":28}", "SANCTIONED");
        return appId;
    }

    private void expectCode(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                            String code) throws Exception {
        mvc.perform(request)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private static String signaturePngBase64() throws java.io.IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(120, 40,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        img.setRGB(10, 10, 0xff0c2540);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        return java.util.Base64.getEncoder().encodeToString(png.toByteArray());
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
        // PLAN Phase 4: the Disbursement Head sends the money and enters the UTR; the loan exists
        // but waits for an Accountant to confirm the transfer.
        act(appId, "disbursement-decision", "6", "DISBURSEMENT_HEAD",
                "{\"decision\":true,\"txnRef\":\"UTR-IT-001\"}", "DISBURSAL_CONFIRMATION_PENDING");
        long loanId = loanIdOf(appId);
        // The borrower sees "money on the way": their loan, the UTR, and a pending lifecycle.
        mvc.perform(get("/api/loan/{id}", loanId).header("Authorization", bearer("7", "BORROWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lifecycleStatus").value("DISBURSEMENT_PENDING"))
                .andExpect(jsonPath("$.data.status").value("DISBURSING"))
                .andExpect(jsonPath("$.data.disbursalTxnRef").value("UTR-IT-001"));
        mvc.perform(get("/api/applications/{id}", appId).header("Authorization", bearer("7", "BORROWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISBURSAL_CONFIRMATION_PENDING"))
                .andExpect(jsonPath("$.data.loanId").value(loanId));
        // The Accountant's queue.
        mvc.perform(get("/api/applications").param("status", "DISBURSAL_CONFIRMATION_PENDING")
                        .header("Authorization", bearer("3", "ACCOUNTANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id==" + appId + ")]").exists());
        confirm(appId, "3", "ACCOUNTANT", "ACTIVE");
        assertThat(loanIdOf(appId)).isEqualTo(loanId);

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

    /**
     * PLAN Phase 4: ADMIN still passes every role check but no longer bypasses separation of duties,
     * so an ADMIN who sanctioned cannot release, and nobody who sanctioned or released can confirm.
     */
    @Test
    void sod_adminNoLongerWalksAnApplicationToActiveSolo() throws Exception {
        long appId = createApplication(10L);
        act(appId, "submit-kyc", "10", "BORROWER", null, "KYC_PENDING");
        act(appId, "assign", "10", "ADMIN", "{\"executiveId\":10}", "CREDIT_EXEC_PENDING");
        act(appId, "sanction", "10", "ADMIN",
                "{\"sanctionedAmountPaise\":100000,\"salaryCreditDay\":28}", "SANCTIONED");
        recordEsign(appId);
        act(appId, "accept-offer", "10", "BORROWER", "{\"amountPaise\":100000}", "DISBURSEMENT_PENDING");

        // sanctioner ≠ disburser
        expectSod(post("/api/applications/{id}/disbursement-decision", appId), "10", "ADMIN",
                "{\"decision\":true,\"txnRef\":\"ADMIN-TXN-10\"}");
        assertStatusEquals(appId, "DISBURSEMENT_PENDING");
        act(appId, "disbursement-decision", "6", "DISBURSEMENT_HEAD",
                "{\"decision\":true,\"txnRef\":\"ADMIN-TXN-10\"}", "DISBURSAL_CONFIRMATION_PENDING");
        // confirmer ≠ sanctioner, confirmer ≠ disburser (both as ADMIN, who passes the role check)
        expectSod(post("/api/applications/{id}/disbursal/confirm", appId), "10", "ADMIN", "{}");
        expectSod(post("/api/applications/{id}/disbursal/confirm", appId), "6", "ADMIN", "{}");
        expectSod(post("/api/applications/{id}/disbursal/return", appId), "6", "ADMIN", "{\"notes\":\"x\"}");
        assertStatusEquals(appId, "DISBURSAL_CONFIRMATION_PENDING");
        // A third person confirms.
        confirm(appId, "1", "ADMIN", "ACTIVE");
    }

    /** PLAN Phase 4: payment recorder ≠ verifier, ADMIN included; a different staff member verifies. */
    @Test
    void sod_paymentRecorderCannotVerify() throws Exception {
        long loanId = loanIdOf(walkToActive(50L));
        MvcResult recorded = mvc.perform(post("/api/loan/{id}/repayments/admin-record", loanId)
                        .header("Authorization", bearer("1", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":100000,\"method\":\"UPI\",\"txnRef\":\"UTR-SOD-50\",\"paidOn\":\""
                                + LocalDate.now(ZoneId.of("Asia/Kolkata")) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_VERIFICATION"))
                .andReturn();
        long paymentId = data(recorded).get("id").asLong();

        expectSod(post("/api/loan/{id}/repayments/{pid}/verify", loanId, paymentId), "1", "ADMIN", null);
        mvc.perform(post("/api/loan/{id}/repayments/{pid}/verify", loanId, paymentId)
                        .header("Authorization", bearer("3", "ACCOUNTANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"));
        assertThat(jdbc.queryForMap("select recorded_by, decided_by from payment where id = ?", paymentId))
                .containsEntry("recorded_by", 1L).containsEntry("decided_by", 3L);
    }

    /** PLAN Phase 4: the collections officer who logged a payment cannot validate it, ADMIN included. */
    @Test
    void sod_collectionsPaymentRecorderCannotValidate() throws Exception {
        long loanId = loanIdOf(walkToActive(51L));
        MvcResult opened = mvc.perform(post("/api/collections/cases")
                        .header("Authorization", bearer("1", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanId\":" + loanId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        String caseId = data(opened).get("id").asText();
        MvcResult raised = mvc.perform(post("/api/collections/cases/{id}/payments", caseId)
                        .header("Authorization", bearer("1", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PART_PAYMENT\",\"amountPaise\":40000,\"txnRef\":\"UTR-COL-51\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String paymentId = data(raised).get("id").asText();

        expectSod(post("/api/collections/payments/{id}/validate", paymentId), "1", "ADMIN", "{\"accept\":true}");
        mvc.perform(post("/api/collections/payments/{id}/validate", paymentId)
                        .header("Authorization", bearer("3", "ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VALIDATED"));
    }

    /**
     * CC-01, PLAN Phase 4: day 0 is the confirmed date. The UTR went in three days before the
     * Accountant confirmed (simulated by back-dating the pending loan), and the due date moves with
     * the confirmation: today + 14 at 23:59:59 IST.
     */
    @Test
    void cc01_dueDateIsFixedFromTheConfirmedDateNotTheUtrEntry() throws Exception {
        long appId = walkToConfirmation(52L);
        long loanId = loanIdOf(appId);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        jdbc.update("update loan set disbursed_on = ?, due_date = ? where id = ?",
                today.minusDays(3), today.plusDays(11), loanId);
        // Payments wait for the confirmation too.
        mvc.perform(post("/api/loan/{id}/repayments", loanId)
                        .header("Authorization", bearer("52", "BORROWER"))
                        .header("Idempotency-Key", "cc01-52")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":100000,\"method\":\"UPI\",\"txnRef\":\"UTR-EARLY\",\"proofUrl\":\"loan/repayment-proof/52/x.png\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("LOAN_NOT_ACTIVE"));

        confirm(appId, "3", "ACCOUNTANT", "ACTIVE");

        LocalDate due = today.plusDays(14);
        assertThat(jdbc.queryForMap("select disbursed_on, due_date, due_at, tenure_days, lifecycle_status, status,"
                        + " net_disbursed, total_repayable from loan where id = ?", loanId))
                .containsEntry("disbursed_on", java.sql.Date.valueOf(today))
                .containsEntry("due_date", java.sql.Date.valueOf(due))
                .containsEntry("due_at", java.sql.Timestamp.from(
                        due.atTime(23, 59, 59).atZone(ZoneId.of("Asia/Kolkata")).toInstant()))
                .containsEntry("tenure_days", 14)
                .containsEntry("lifecycle_status", "ACTIVE")
                .containsEntry("status", "ACTIVE")
                .containsEntry("net_disbursed", 74200L)
                .containsEntry("total_repayable", 100000L);
    }

    /** A wrong UTR goes back to the Disbursement Head; the unconfirmed loan is dropped and re-minted. */
    @Test
    void accountantReturnsAWrongUtrToTheDisbursementHead() throws Exception {
        long appId = walkToConfirmation(53L);
        long firstLoan = loanIdOf(appId);
        mvc.perform(post("/api/applications/{id}/disbursal/return", appId)
                        .header("Authorization", bearer("3", "ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"UTR not on the bank statement\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISBURSEMENT_PENDING"));
        assertThat(jdbc.queryForObject("select count(*) from loan where id = ?", Long.class, firstLoan)).isZero();

        act(appId, "disbursement-decision", "6", "DISBURSEMENT_HEAD",
                "{\"decision\":true,\"txnRef\":\"UTR-53-FIXED\"}", "DISBURSAL_CONFIRMATION_PENDING");
        confirm(appId, "3", "ACCOUNTANT", "ACTIVE");
        assertThat(jdbc.queryForObject("select disbursal_txn_ref from loan where id = ?", String.class, loanIdOf(appId)))
                .isEqualTo("UTR-53-FIXED");
    }

    /** CC-23: while the disbursal awaits confirmation, a new application is blocked. */
    @Test
    void cc23_applyAgainWhileAwaitingDisbursalConfirmationIsBlocked() throws Exception {
        walkToConfirmation(54L);
        mvc.perform(post("/api/applications")
                        .header("Authorization", bearer("54", "BORROWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":54}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_APPLICATION"));
    }

    /** V99: the new status is allowed by the database check. */
    @Test
    void v99_statusCheckAcceptsTheConfirmationState() {
        assertThat(jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint"
                + " where conname = 'loan_application_status_check'", String.class))
                .contains("DISBURSAL_CONFIRMATION_PENDING");
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
                "{\"decision\":true,\"txnRef\":\"UTR-8\"}", "DISBURSAL_CONFIRMATION_PENDING");
        confirm(appId, "3", "ACCOUNTANT", "ACTIVE");

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

    // ---- PLAN Phase 7: complaints, disputes, roles ------------------------------------

    private static final String SUPPORT_A = "30";
    private static final String SUPPORT_B = "31";
    private static final String COMPLIANCE = "32";

    private JsonNode fileComplaint(long customerId, String key, String body) throws Exception {
        return data(mvc.perform(post("/api/complaints").header("Authorization", bearer(String.valueOf(customerId), "BORROWER"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    /**
     * A borrower files a ticket (DN-GRV-xxxxx, SLA dates, the grievance officer from config), a replay with the same
     * key returns it, another borrower cannot read it, COMPLIANCE reads the queue but cannot act, SUPPORT acknowledges,
     * reviews and resolves it (a note is required), and the borrower reads the resolution. The SLA sweep flags a
     * missed milestone once.
     */
    @Test
    void complaint_fileAcknowledgeReviewResolveAndTheSlaSweep() throws Exception {
        long loanId = loanIdOf(walkToActive(200L));
        String body = "{\"category\":\"CHARGES\",\"body\":\"I was charged twice\",\"loanId\":" + loanId + "}";
        JsonNode filed = fileComplaint(200L, "grv-200-a", body);
        long id = filed.get("id").asLong();
        assertThat(filed.get("ticketNo").asText()).matches("DN-GRV-\\d{5}");
        assertThat(filed.get("status").asText()).isEqualTo("OPEN");
        assertThat(filed.get("ackDueAt").asText()).isNotBlank();
        assertThat(filed.has("grievanceOfficer")).isTrue();
        assertThat(fileComplaint(200L, "grv-200-a", body).get("id").asLong()).isEqualTo(id);
        expectCode(post("/api/complaints").header("Authorization", bearer("200", "BORROWER"))
                .contentType(MediaType.APPLICATION_JSON).content(body), "IDEMPOTENCY_KEY_REQUIRED");
        // Another customer: not their ticket, and not their loan.
        expectCode(get("/api/complaints/{id}", id).header("Authorization", bearer("201", "BORROWER")), "FORBIDDEN_OWNER");
        expectCode(post("/api/complaints").header("Authorization", bearer("201", "BORROWER")).header("Idempotency-Key", "k201")
                .contentType(MediaType.APPLICATION_JSON).content(body), "FORBIDDEN_OWNER");
        // A borrower cannot read the staff queue.
        mvc.perform(get("/api/staff/complaints").header("Authorization", bearer("200", "BORROWER")))
                .andExpect(status().isForbidden());

        // COMPLIANCE reads, never writes (403 at the filter).
        mvc.perform(get("/api/staff/complaints?status=OPEN").header("Authorization", bearer(COMPLIANCE, "COMPLIANCE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id==" + id + ")].ticketNo").exists());
        mvc.perform(post("/api/staff/complaints/{id}/acknowledge", id).header("Authorization", bearer(COMPLIANCE, "COMPLIANCE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("READ_ONLY_ROLE"));
        // A collections officer is not on the grievance desk.
        expectCode(post("/api/staff/complaints/{id}/acknowledge", id).header("Authorization", bearer(HEAD_A, "COLLECTION_HEAD"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"), "FORBIDDEN_ROLE");

        String s = bearer(SUPPORT_A, "SUPPORT");
        assertThat(extensionCall(post("/api/staff/complaints/{id}/acknowledge", id), s, "{\"note\":\"Looking into it\"}")
                .get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(extensionCall(post("/api/staff/complaints/{id}/status", id), s, "{\"status\":\"IN_REVIEW\"}")
                .get("status").asText()).isEqualTo("IN_REVIEW");
        expectCode(post("/api/staff/complaints/{id}/resolve", id).header("Authorization", s)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), "NOTE_REQUIRED");
        mvc.perform(post("/api/staff/complaints/{id}/status", id).header("Authorization", s)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STALE_STATE"));
        assertThat(extensionCall(post("/api/staff/complaints/{id}/resolve", id), s,
                "{\"note\":\"The second charge is refunded.\"}").get("status").asText()).isEqualTo("RESOLVED");
        JsonNode mine = data(mvc.perform(get("/api/complaints/{id}", id).header("Authorization", bearer("200", "BORROWER")))
                .andExpect(status().isOk()).andReturn());
        assertThat(mine.get("resolutionNote").asText()).isEqualTo("The second charge is refunded.");
        JsonNode staffView = extensionCall(get("/api/staff/complaints/{id}", id), bearer(COMPLIANCE, "COMPLIANCE"), null);
        assertThat(staffView.get("events")).extracting(e -> e.get("action").asText())
                .containsExactly("FILED", "ACKNOWLEDGED", "IN_REVIEW", "RESOLVED");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "update complaint_event set note = 'x' where complaint_id = ?", id)).hasMessageContaining("append-only");

        // SLA: a second ticket whose acknowledgement deadline has passed is flagged once.
        long late = fileComplaint(200L, "grv-200-b", "{\"category\":\"OTHER\",\"body\":\"No reply yet\"}").get("id").asLong();
        jdbc.update("update complaint set ack_due_at = now() - interval '1 hour' where id = ?", late);
        assertThat(complaints.flagBreaches()).isGreaterThanOrEqualTo(1);
        assertThat(complaints.flagBreaches()).isZero();
        mvc.perform(get("/api/staff/complaints?breached=true").header("Authorization", s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id==" + late + ")].breached[0]").value("ACK"));
    }

    /**
     * EXT-27 end to end: an approved extension offer is open on an overdue loan; the borrower files a LOAN_DISPUTE
     * complaint on it → the loan is DISPUTED and the offer cancelled; no new offer can be made; the staff who
     * raises a second dispute cannot release it (maker-checker); another SUPPORT member releases it and offers work
     * again. Every hold change is a loan_hold_event row.
     */
    @Test
    void dispute_aLoanDisputeComplaintHoldsTheLoanAndCancelsTheOpenOfferThenRelease() throws Exception {
        long loanId = overdueLoan(202L, 3)[1];
        JsonNode offered = extensionCall(post("/api/collections/loans/{id}/extensions", loanId),
                bearer(HEAD_A, "COLLECTION_HEAD"), "{\"callNote\":\"Borrower asked for 14 days\"}");
        String extId = offered.get("id").asText();
        extensionCall(post("/api/collections/extensions/{id}/approve", extId), bearer(HEAD_B, "COLLECTION_HEAD"), null);

        JsonNode ticket = fileComplaint(202L, "grv-202", "{\"category\":\"LOAN_DISPUTE\",\"body\":\"I already repaid this"
                + " loan\",\"loanId\":" + loanId + "}");
        assertThat(jdbc.queryForObject("select hold_status from loan where id = ?", String.class, loanId)).isEqualTo("DISPUTED");
        assertThat(jdbc.queryForMap("select status, cancel_reason from loan_extension where id = ?::uuid", extId))
                .containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "DISPUTED");
        assertThat(jdbc.queryForObject("select complaint_id from loan_hold_event where loan_id = ?", Long.class, loanId))
                .isEqualTo(ticket.get("id").asLong());
        expectCode(post("/api/collections/loans/{id}/extensions", loanId).header("Authorization", bearer(HEAD_A, "COLLECTION_HEAD"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"callNote\":\"again\"}"), "ON_HOLD");
        mvc.perform(post("/api/loans/{id}/dispute", loanId).header("Authorization", bearer(SUPPORT_A, "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());
        expectCode(post("/api/loans/{id}/dispute/release", loanId).header("Authorization", bearer("202", "BORROWER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"), "FORBIDDEN_ROLE");

        // The system raised this one, so any SUPPORT member may release it.
        assertThat(extensionCall(post("/api/loans/{id}/dispute/release", loanId), bearer(SUPPORT_A, "SUPPORT"),
                "{\"note\":\"Payment checked: not repaid\"}").get("holdStatus").asText()).isEqualTo("NONE");

        // A staff-raised dispute: the raiser cannot lift it.
        extensionCall(post("/api/loans/{id}/dispute", loanId), bearer(SUPPORT_A, "SUPPORT"), "{\"reason\":\"Borrower called\"}");
        expectSod(post("/api/loans/{id}/dispute/release", loanId), SUPPORT_A, "SUPPORT", "{\"note\":\"done\"}");
        extensionCall(post("/api/loans/{id}/dispute/release", loanId), bearer(SUPPORT_B, "SUPPORT"), "{\"note\":\"Resolved\"}");
        assertThat(jdbc.queryForObject("select hold_status from loan where id = ?", String.class, loanId)).isEqualTo("NONE");
        JsonNode holds = extensionCall(get("/api/loans/{id}/holds", loanId), bearer(COMPLIANCE, "COMPLIANCE"), null);
        assertThat(holds).extracting(h -> h.get("toHold").asText()).containsExactly("DISPUTED", "NONE", "DISPUTED", "NONE");
        // Offers work again.
        extensionCall(post("/api/collections/loans/{id}/extensions", loanId), bearer(HEAD_A, "COLLECTION_HEAD"),
                "{\"callNote\":\"After the dispute\"}");
    }

    /** CC-64: the acknowledgement deadline skips the holidays an ADMIN keeps in the calendar. */
    @Test
    void cc64_theSlaSkipsHolidaysFromTheAdminCalendar() throws Exception {
        LocalDate today = LocalDate.now(IST);
        String admin = bearer(ADMIN_A, "ADMIN");
        expectCode(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/holidays/{d}",
                today.plusDays(1)).header("Authorization", bearer(SUPPORT_A, "SUPPORT"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"), "FORBIDDEN_ROLE");
        try {
            for (int d = 1; d <= 12; d++) {
                extensionCall(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/admin/holidays/{d}", today.plusDays(d)), admin, "{\"name\":\"Test holiday " + d + "\"}");
            }
            JsonNode filed = fileComplaint(208L, "grv-208", "{\"category\":\"OTHER\",\"body\":\"Holiday test\"}");
            LocalDate ackDay = java.time.Instant.parse(filed.get("ackDueAt").asText()).atZone(IST).toLocalDate();
            LocalDate resolveDay = java.time.Instant.parse(filed.get("resolveDueAt").asText()).atZone(IST).toLocalDate();
            assertThat(ackDay).isAfter(today.plusDays(12));
            assertThat(resolveDay).isEqualTo(today.plusDays(15)); // calendar days: holidays do not move it
        } finally {
            for (int d = 1; d <= 12; d++) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/admin/holidays/{d}", today.plusDays(d)).header("Authorization", admin));
            }
        }
    }

    /** CC-71: a borrower who says "I didn't ask" on an extension offer gets a complaint ticket. */
    @Test
    void cc71_didNotAskOnAnExtensionOpensAComplaint() throws Exception {
        long loanId = overdueLoan(204L, 3)[1];
        JsonNode offered = extensionCall(post("/api/collections/loans/{id}/extensions", loanId),
                bearer(HEAD_A, "COLLECTION_HEAD"), "{\"callNote\":\"Call note\"}");
        String extId = offered.get("id").asText();
        JsonNode approved = extensionCall(post("/api/collections/extensions/{id}/approve", extId),
                bearer(HEAD_B, "COLLECTION_HEAD"), null);
        extensionCall(post("/api/loans/{l}/extensions/{e}/decline", loanId, extId), bearer("204", "BORROWER"),
                "{\"reason\":\"DID_NOT_ASK\",\"version\":" + approved.get("version").asLong() + "}");
        JsonNode mine = data(mvc.perform(get("/api/complaints").header("Authorization", bearer("204", "BORROWER")))
                .andExpect(status().isOk()).andReturn());
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("category").asText()).isEqualTo("EXTENSION_NOT_REQUESTED");
        assertThat(mine.get(0).get("loanId").asLong()).isEqualTo(loanId);
    }

    /**
     * Roles: SUPPORT never moves money or a due date (422 FORBIDDEN_ROLE), COMPLIANCE never writes (403), FINANCE may
     * act on refunds. balance_adjustment takes only listed reason codes and is append-only (V106).
     */
    @Test
    void roles_supportIsMoneyBlockedComplianceIsReadOnlyAndAdjustmentsAreAudited() throws Exception {
        long loanId = loanIdOf(walkToActive(206L));
        String support = bearer(SUPPORT_A, "SUPPORT");
        expectCode(post("/api/loan/{id}/repayments/admin-record", loanId).header("Authorization", support)
                .contentType(MediaType.APPLICATION_JSON).content(repayment(100000, "UTR206", LocalDate.now(IST))), "FORBIDDEN_ROLE");
        expectCode(post("/api/loan/{id}/repayments/{p}/reverse", loanId, 1L).header("Authorization", support)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"), "FORBIDDEN_ROLE");
        expectCode(post("/api/refund-cases/{id}/cancel", 1L).header("Authorization", support)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"), "FORBIDDEN_ROLE");
        expectCode(post("/api/collections/loans/{id}/extensions", loanId).header("Authorization", support)
                .contentType(MediaType.APPLICATION_JSON).content("{\"callNote\":\"x\"}"), "FORBIDDEN_ROLE");
        mvc.perform(post("/api/loan/{id}/repayments/admin-record", loanId).header("Authorization", bearer(COMPLIANCE, "COMPLIANCE"))
                        .contentType(MediaType.APPLICATION_JSON).content(repayment(100000, "UTR206", LocalDate.now(IST))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/refund-cases").header("Authorization", bearer(COMPLIANCE, "COMPLIANCE")))
                .andExpect(status().isOk());

        // FINANCE acts on a refund case, and the decision is a balance_adjustment row.
        long paid = submit("/api/loan/{id}/repayments/admin-record", loanId, bearer(ADMIN_A, "ADMIN"), "k-206-1",
                repayment(100500, "UTR-OVER-206", LocalDate.now(IST)));
        verifyPayment(loanId, paid);
        long caseId = jdbc.queryForObject("select id from refund_case where payment_id = ?", Long.class, paid);
        extensionCall(post("/api/refund-cases/{id}/refunded", caseId), bearer("33", "FINANCE"), "{\"utr\":\"RFND206\"}");
        assertThat(jdbc.queryForMap("select kind, reason_code, approver_id from balance_adjustment where loan_id = ?", loanId))
                .containsEntry("kind", "REFUND").containsEntry("reason_code", "REFUND_PAID").containsEntry("approver_id", 33L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("insert into balance_adjustment (loan_id,"
                + " customer_id, kind, before, after, reason_code) values (?, 206, 'REFUND', '{}', '{}', 'MADE_UP')", loanId))
                .hasMessageContaining("balance_adjustment_reason_fk");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "delete from balance_adjustment where loan_id = ?", loanId)).hasMessageContaining("append-only");
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

    /** A first loan (₹1,000) walked to the Accountant's confirmation desk. */
    private long walkToConfirmation(long customerId) throws Exception {
        long appId = createApplication(customerId);
        String c = String.valueOf(customerId);
        act(appId, "submit-kyc", c, "BORROWER", null, "KYC_PENDING");
        act(appId, "assign", "5", "CREDIT_HEAD", "{\"executiveId\":2}", "CREDIT_EXEC_PENDING");
        act(appId, "sanction", "2", "CREDIT_EXECUTIVE",
                "{\"sanctionedAmountPaise\":100000,\"salaryCreditDay\":28}", "SANCTIONED");
        recordEsign(appId);
        act(appId, "accept-offer", c, "BORROWER", "{\"amountPaise\":100000}", "DISBURSEMENT_PENDING");
        act(appId, "disbursement-decision", "6", "DISBURSEMENT_HEAD",
                "{\"decision\":true,\"txnRef\":\"UTR-" + c + "\"}", "DISBURSAL_CONFIRMATION_PENDING");
        return appId;
    }

    private long walkToActive(long customerId) throws Exception {
        long appId = walkToConfirmation(customerId);
        confirm(appId, "3", "ACCOUNTANT", "ACTIVE");
        return appId;
    }

    private void confirm(long appId, String actorId, String role, String expectedStatus) throws Exception {
        mvc.perform(post("/api/applications/{id}/disbursal/confirm", appId)
                        .header("Authorization", bearer(actorId, role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expectedStatus));
    }

    private void expectSod(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                           String actorId, String role, String body) throws Exception {
        request = request.header("Authorization", bearer(actorId, role)).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.content(body);
        }
        mvc.perform(request)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("SOD_VIOLATION"));
    }

    private long loanIdOf(long appId) throws Exception {
        MvcResult view = mvc.perform(get("/api/applications/{id}", appId)
                        .header("Authorization", bearer("1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loanId", notNullValue()))
                .andReturn();
        return data(view).get("loanId").asLong();
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
