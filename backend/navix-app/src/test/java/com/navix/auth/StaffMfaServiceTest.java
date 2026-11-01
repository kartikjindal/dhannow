package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.brand.BrandProperties;
import com.navix.common.exception.BusinessException;
import com.navix.config.MfaProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** PLAN Phase 7: staff TOTP — RFC 6238 codes, the skew window, replay, encryption at rest and who must use it. */
class StaffMfaServiceTest {

    /** A test key only (32 fixed bytes); real keys come from NAVIX_MFA_ENCRYPTION_KEY. */
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]).replace('A', 'B');
    private static final List<String> REQUIRED = List.of("ADMIN", "FINANCE", "CREDIT_HEAD", "DISBURSEMENT_HEAD");
    private static final BrandProperties BRAND = new BrandProperties("DhanNow", "www.dhannow.com", "9211331169",
            "support@dhannow.com");
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:15Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private StaffMfaService service(boolean enforce) {
        return new StaffMfaService(jdbc, new MfaProperties(enforce, TEST_KEY, REQUIRED), BRAND,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stored(StaffMfaService s, boolean confirmed, Long lastStep) {
        Map<String, Object> row = new HashMap<>();
        row.put("secret_enc", s.encrypt(RFC_SECRET));
        row.put("confirmed_at", confirmed ? java.sql.Timestamp.from(NOW) : null);
        row.put("last_step", lastStep);
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(row));
    }

    private static long step() {
        return NOW.getEpochSecond() / StaffMfaService.STEP_SECONDS;
    }

    @Test
    void theCodeMatchesTheRfc6238Vector() {
        // RFC 6238 appendix B, SHA-1, T = 59 s: 94287082; six digits are its last six.
        assertThat(StaffMfaService.code(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(StaffMfaService.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
    }

    @Test
    void oneStepOfClockSkewEitherWayIsAcceptedTwoIsNot() {
        long now = step();
        assertThat(StaffMfaService.matchingStep(RFC_SECRET, StaffMfaService.code(RFC_SECRET, now - 1), now)).isEqualTo(now - 1);
        assertThat(StaffMfaService.matchingStep(RFC_SECRET, StaffMfaService.code(RFC_SECRET, now + 1), now)).isEqualTo(now + 1);
        assertThat(StaffMfaService.matchingStep(RFC_SECRET, StaffMfaService.code(RFC_SECRET, now - 2), now)).isEqualTo(-1);
        assertThat(StaffMfaService.matchingStep(RFC_SECRET, StaffMfaService.code(RFC_SECRET, now + 2), now)).isEqualTo(-1);
        assertThat(StaffMfaService.matchingStep(RFC_SECRET, "12ab56", now)).isEqualTo(-1);
    }

    @Test
    void theSecretIsEncryptedAtRestAndNeedsTheSameKey() {
        StaffMfaService s = service(true);
        String stored = s.encrypt(RFC_SECRET);
        assertThat(stored).doesNotContain(StaffMfaService.base32(RFC_SECRET));
        assertThat(s.decrypt(stored)).isEqualTo(RFC_SECRET);
        assertThat(s.encrypt(RFC_SECRET)).isNotEqualTo(stored); // a fresh IV every time

        StaffMfaService otherKey = new StaffMfaService(jdbc, new MfaProperties(true,
                Base64.getEncoder().encodeToString(new byte[32]), REQUIRED), BRAND, Clock.systemUTC());
        assertThatThrownBy(() -> otherKey.decrypt(stored)).isInstanceOf(IllegalStateException.class);
        StaffMfaService noKey = new StaffMfaService(jdbc, new MfaProperties(true, "", REQUIRED), BRAND,
                Clock.systemUTC());
        assertThatThrownBy(() -> noKey.encrypt(RFC_SECRET)).hasFieldOrPropertyWithValue("code", "MFA_NOT_CONFIGURED");
    }

    @Test
    void mfaIsRequiredAtSignInForAdminFinanceAndTheHeads() {
        StaffMfaService s = service(true);
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of());
        for (String role : REQUIRED) {
            assertThatThrownBy(() -> s.checkAtLogin(7L, role, null))
                    .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("code", "MFA_ENROLMENT_REQUIRED");
        }
        assertThatCode(() -> s.checkAtLogin(7L, "ACCOUNTANT", null)).doesNotThrowAnyException();
        assertThatCode(() -> s.checkAtLogin(7L, "SUPPORT", null)).doesNotThrowAnyException();
        // Local runs can switch enforcement off.
        assertThatCode(() -> service(false).checkAtLogin(7L, "ADMIN", null)).doesNotThrowAnyException();
    }

    @Test
    void anEnrolledStaffMemberNeedsTheCurrentCodeOnceOnly() {
        StaffMfaService s = service(true);
        stored(s, true, null);
        assertThatThrownBy(() -> s.checkAtLogin(7L, "ACCOUNTANT", null))
                .hasFieldOrPropertyWithValue("code", "MFA_REQUIRED");
        assertThatThrownBy(() -> s.checkAtLogin(7L, "ADMIN", "000000"))
                .hasFieldOrPropertyWithValue("code", "INVALID_MFA_CODE");

        s.checkAtLogin(7L, "ADMIN", StaffMfaService.code(RFC_SECRET, step()));
        verify(jdbc).update("update staff_totp_secret set last_step = ? where staff_id = ?", step(), 7L);

        // The same code again (its step is now the last one used) is a replay.
        stored(s, true, step());
        assertThatThrownBy(() -> s.checkAtLogin(7L, "ADMIN", StaffMfaService.code(RFC_SECRET, step())))
                .hasFieldOrPropertyWithValue("code", "INVALID_MFA_CODE");
    }

    @Test
    void enrolmentStoresAnEncryptedSecretAndConfirmNeedsAValidCode() {
        StaffMfaService s = service(true);
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of());
        StaffMfaService.Enrolment e = s.enrol(7L, "asha@dhannow.com");
        assertThat(e.secret()).matches("[A-Z2-7]{32}");
        assertThat(e.otpauthUri()).startsWith("otpauth://totp/DhanNow:asha%40dhannow.com?secret=" + e.secret())
                .contains("issuer=DhanNow").contains("digits=6").contains("period=30");
        verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith("insert into staff_totp_secret"), eq(7L),
                org.mockito.ArgumentMatchers.argThat((String enc) -> !enc.contains(e.secret())), any());

        stored(s, false, null);
        assertThatThrownBy(() -> s.confirm(7L, "000000")).hasFieldOrPropertyWithValue("code", "INVALID_MFA_CODE");
        s.confirm(7L, StaffMfaService.code(RFC_SECRET, step()));

        stored(s, true, null);
        assertThatThrownBy(() -> s.enrol(7L, "asha@dhannow.com"))
                .hasFieldOrPropertyWithValue("code", "MFA_ALREADY_ENROLLED");
    }

    @Test
    void base32MatchesTheRfc4648Alphabet() {
        assertThat(StaffMfaService.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(new MfaProperties(true, "c2hvcnQ=", REQUIRED).keyBytes()).isNull();
        assertThat(new MfaProperties(true, TEST_KEY, REQUIRED).keyBytes()).hasSize(32);
    }
}
