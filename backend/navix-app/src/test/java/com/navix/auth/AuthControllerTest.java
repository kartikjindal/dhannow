package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.auth.AuthDtos.BorrowerLoginRequest;
import com.navix.auth.AuthDtos.BorrowerPasswordLoginRequest;
import com.navix.auth.AuthDtos.ForgotPasswordRequest;
import com.navix.auth.AuthDtos.StaffLoginRequest;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.common.security.JwtService;
import com.navix.config.StaffSessionRegistry;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    /** A test-only staff password; nothing is seeded in the migrations. */
    private static final String SEEDED_HASH = new BCryptPasswordEncoder().encode("Test@12345");
    /** Matches {@code navix.auth.ttl-seconds}'s default (1 day). */
    private static final long STAFF_TTL_SECONDS = 86400L;

    @Mock private StaffUserRepository staffRepository;
    @Mock private StaffMfaService mfa;
    @Mock private BorrowerOtpService otpService;
    @Mock private CustomerProfileRepository profileRepository;
    @Mock private BorrowerCredentialRepository credentialRepository;
    @Mock private PasswordResetService passwordResetService;
    @Mock private com.navix.iam.service.InviteService inviteService;
    @Mock private BorrowerMobileRepository mobileRepository;
    @Mock private StaffSessionRegistry staffSessionRegistry;
    @Mock private RefreshTokenService refreshTokens;
    @Mock private DeviceService devices;

    private AuthController controller;
    private JwtService jwt;

    @BeforeEach
    void setUp() {
        jwt = new JwtService("test-secret-test-secret-test-secret", 3600, 3600);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        controller = new AuthController(staffRepository, jwt, encoder, otpService, profileRepository,
                credentialRepository, passwordResetService, inviteService, mobileRepository,
                new AttemptLimiter(new InMemoryAuthStateStore()), new CaptchaVerifier("", ""), staffSessionRegistry, mfa,
                refreshTokens, devices);
        // @Value-injected field, so there is no constructor slot for it — set what Spring would.
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "staffTtlSeconds", STAFF_TTL_SECONDS);
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    /** A mobile that has already claimed {@code customerId}. */
    private BorrowerMobile claim(long customerId, String mobile) {
        BorrowerMobile m = new BorrowerMobile();
        m.setCustomerId(customerId);
        m.setMobile(mobile);
        return m;
    }

    private StaffUser admin() {
        StaffUser s = new StaffUser();
        s.setEmail("meera.krishnan@navix.example");
        s.setName("Meera Krishnan");
        s.setRole(StaffRole.ADMIN);
        s.setStatus(StaffStatus.ACTIVE);
        s.setPasswordHash(SEEDED_HASH);
        return s;
    }

    @Test
    void staffLogin_succeeds_withSeededDefaultPassword() {
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin()));

        var resp = controller.staffLogin(new StaffLoginRequest("meera.krishnan@navix.example", "Test@12345", null, null));

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData().role()).isEqualTo("ADMIN");
        JwtService.Principal p = jwt.verify(resp.getData().token());
        assertThat(p.role()).isEqualTo("ADMIN");
        assertThat(p.audience()).isEqualTo(JwtService.AUDIENCE_STAFF);
        assertThat(p.sessionId()).isNotBlank(); // a fresh session id is minted on first login
    }

    /** PLAN Phase 7: the TOTP step runs after the password, and its failure keeps the attempt on the limiter. */
    @Test
    void staffLogin_mfaRequiredForTheRoleBlocksAPasswordOnlySignIn() {
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin()));
        org.mockito.Mockito.doThrow(new BusinessException("MFA_REQUIRED", "Enter the code"))
                .when(mfa).checkAtLogin(any(), org.mockito.ArgumentMatchers.eq("ADMIN"), org.mockito.ArgumentMatchers.isNull());

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> controller.staffLogin(
                    new StaffLoginRequest("meera.krishnan@navix.example", "Test@12345", null, null)))
                    .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("code", "MFA_REQUIRED");
        }
        // Three tries without a code used up the three attempts: the code cannot be ground out with the password.
        assertThatThrownBy(() -> controller.staffLogin(
                new StaffLoginRequest("meera.krishnan@navix.example", "Test@12345", null, null, "123456")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Too many");
        verify(staffRepository, never()).save(any());
    }

    @Test
    void staffLogin_passesTheCodeToTheMfaCheck() {
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin()));

        var resp = controller.staffLogin(
                new StaffLoginRequest("meera.krishnan@navix.example", "Test@12345", null, null, "654321"));

        assertThat(resp.isSuccess()).isTrue();
        verify(mfa).checkAtLogin(any(), org.mockito.ArgumentMatchers.eq("ADMIN"), org.mockito.ArgumentMatchers.eq("654321"));
    }

    @Test
    void staffLogin_rejectsWrongPassword() {
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin()));
        assertThatThrownBy(() ->
                controller.staffLogin(new StaffLoginRequest("meera.krishnan@navix.example", "wrong", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid");
    }

    @Test
    void staffLogin_rejectsSecondSessionWithSessionConflict() {
        StaffUser admin = admin();
        admin.setActiveSessionId("existing-session");
        admin.setActiveSessionAt(java.time.Instant.now());
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> controller.staffLogin(
                new StaffLoginRequest("meera.krishnan@navix.example", "Test@12345", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already signed in");
        verify(staffRepository, never()).save(any());
    }

    @Test
    void staffLogin_allowsLoginWhenStoredSessionIsOlderThanTheStaffTtl() {
        // A closed browser / cleared cookie / expired token leaves active_session_id set forever;
        // once active_session_at is older than the token's own TTL it can no longer back a live
        // session, so the conflict must NOT fire even without `force`.
        StaffUser admin = admin();
        admin.setActiveSessionId("stale-session");
        admin.setActiveSessionAt(java.time.Instant.now().minusSeconds(STAFF_TTL_SECONDS + 60));
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin));

        var resp = controller.staffLogin(
                new StaffLoginRequest("meera.krishnan@navix.example", "Test@12345", null, null));

        assertThat(resp.isSuccess()).isTrue();
    }

    @Test
    void staffLogin_rejectsSecondSessionWhenStoredSessionIsStillWithinTheStaffTtl() {
        StaffUser admin = admin();
        admin.setActiveSessionId("recent-session");
        admin.setActiveSessionAt(java.time.Instant.now().minusSeconds(60));
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> controller.staffLogin(
                new StaffLoginRequest("meera.krishnan@navix.example", "Test@12345", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already signed in");
    }

    @Test
    void staffLogin_forceOverwritesTheSession() {
        StaffUser admin = admin();
        admin.setActiveSessionId("existing-session");
        admin.setActiveSessionAt(java.time.Instant.now());
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin));

        var resp = controller.staffLogin(
                new StaffLoginRequest("meera.krishnan@navix.example", "Test@12345", true, null));

        assertThat(resp.isSuccess()).isTrue();
        JwtService.Principal p = jwt.verify(resp.getData().token());
        assertThat(p.sessionId()).isNotEqualTo("existing-session");
        assertThat(admin.getActiveSessionId()).isEqualTo(p.sessionId());
        verify(staffRepository).save(admin);
    }

    @Test
    void staffLogout_clearsOnlyTheCallersOwnSession() {
        StaffUser admin = admin();
        admin.setId(9L);
        admin.setActiveSessionId("my-session");
        when(staffRepository.findById(9L)).thenReturn(Optional.of(admin));
        ActorContext.set(new CurrentActor("9", "Meera Krishnan", "ADMIN"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(com.navix.config.JwtAuthFilter.STAFF_SESSION_ID_ATTR, "my-session");

        controller.staffLogout(request);

        assertThat(admin.getActiveSessionId()).isNull();
        verify(staffSessionRegistry).invalidate(9L);
    }

    @Test
    void staffLogout_doesNotClearASessionThatAlreadySupersededTheCallersToken() {
        StaffUser admin = admin();
        admin.setId(9L);
        admin.setActiveSessionId("newer-session"); // superseded this token by a later forced login
        when(staffRepository.findById(9L)).thenReturn(Optional.of(admin));
        ActorContext.set(new CurrentActor("9", "Meera Krishnan", "ADMIN"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(com.navix.config.JwtAuthFilter.STAFF_SESSION_ID_ATTR, "my-old-session");

        controller.staffLogout(request);

        assertThat(admin.getActiveSessionId()).isEqualTo("newer-session");
    }

    @Test
    void borrowerLogin_rejectsWrongOtp() {
        when(otpService.verify("9819000001", "000000", OtpVerifierPort.LOGIN)).thenReturn(false);
        assertThatThrownBy(() ->
                controller.borrowerLogin(new BorrowerLoginRequest("9819000001", "000000", null)))
                .isInstanceOf(BusinessException.class);
    }

    /** CC-24: a number's first sign-in gets a server-generated id (DN + sequence), not one derived from the mobile. */
    @Test
    void cc24_theFirstSignInGetsAServerGeneratedCustomerId() {
        when(otpService.verify("98190 00001", "123456", OtpVerifierPort.LOGIN)).thenReturn(true);
        when(mobileRepository.findByMobile("9819000001")).thenReturn(Optional.empty());
        when(mobileRepository.nextCustomerId()).thenReturn(1L);
        when(mobileRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        var resp = controller.borrowerLogin(new BorrowerLoginRequest("98190 00001", "123456", "Asha"));

        assertThat(resp.getData().customerId()).isEqualTo(1L);
        assertThat(resp.getData().customerCode()).isEqualTo("DN0000001");
        JwtService.Principal p = jwt.verify(resp.getData().token());
        assertThat(p.role()).isEqualTo("BORROWER");
        assertThat(p.audience()).isEqualTo(JwtService.AUDIENCE_BORROWER);
        assertThat(p.id()).isEqualTo("1");
    }

    /**
     * CC-24: 9819000001 and 9919000001 share their last seven digits. Each keeps the id it was given, so neither
     * can land in the other's account and nobody is refused (no CUSTOMER_ID_COLLISION any more).
     */
    @Test
    void cc24_aReturningNumberKeepsItsIdAndALookAlikeNumberIsAnotherCustomer() {
        when(otpService.verify("9919000001", "123456", OtpVerifierPort.LOGIN)).thenReturn(true);
        when(mobileRepository.findByMobile("9919000001")).thenReturn(Optional.of(claim(42L, "9919000001")));

        var resp = controller.borrowerLogin(new BorrowerLoginRequest("9919000001", "123456", null));

        assertThat(resp.getData().customerId()).isEqualTo(42L);
        verify(mobileRepository, never()).saveAndFlush(any());
    }

    /** Password sign-in never registers a number: only a registered number with a password can get in. */
    @Test
    void borrowerPasswordLogin_writesNothingAndChecksThePassword() {
        BorrowerCredential cred = new BorrowerCredential();
        cred.setCustomerId(9000001L);
        cred.setPasswordHash(new BCryptPasswordEncoder().encode("right-password"));
        when(mobileRepository.findByMobile("9819000001")).thenReturn(Optional.of(claim(9000001L, "9819000001")));
        when(credentialRepository.findById(9000001L)).thenReturn(Optional.of(cred));

        assertThatThrownBy(() -> controller.borrowerPasswordLogin(
                new BorrowerPasswordLoginRequest("9819000001", "wrong-password", null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_CREDENTIALS");

        var resp = controller.borrowerPasswordLogin(
                new BorrowerPasswordLoginRequest("9819000001", "right-password", null));
        assertThat(resp.getData().customerId()).isEqualTo(9000001L);
        verify(mobileRepository, never()).saveAndFlush(any());
        verify(mobileRepository, never()).save(any());
    }

    @Test
    void borrowerPasswordLogin_anUnregisteredNumberHasNoPassword() {
        when(mobileRepository.findByMobile("9919000001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.borrowerPasswordLogin(
                new BorrowerPasswordLoginRequest("9919000001", "anything", null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "NO_PASSWORD_SET");
        verify(credentialRepository, never()).findById(any());
    }

    @Test
    void borrowerForgotPassword_returnsGenericAckAndForwardsEmailOnly() {
        var resp = controller.borrowerForgotPassword(new ForgotPasswordRequest("a@navix.test", null));

        assertThat(resp.getData().message()).contains("If that email matches an account");
        verify(passwordResetService).requestBorrowerReset("a@navix.test");
    }

    @Test
    void borrowerForgotPassword_rateLimitsAfterThreeRequestsPerEmail() {
        for (int i = 0; i < 3; i++) {
            controller.borrowerForgotPassword(new ForgotPasswordRequest("a@navix.test", null));
        }
        assertThatThrownBy(() ->
                controller.borrowerForgotPassword(new ForgotPasswordRequest("a@navix.test", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("TOO_MANY_ATTEMPTS");
        // A different email is unaffected — the limit is per-email, not global.
        controller.borrowerForgotPassword(new ForgotPasswordRequest("b@navix.test", null));
    }

    @Test
    void staffForgotPassword_rateLimitsAfterThreeRequestsPerEmail() {
        for (int i = 0; i < 3; i++) {
            controller.staffForgotPassword(new ForgotPasswordRequest("meera.krishnan@navix.example", null));
        }
        assertThatThrownBy(() ->
                controller.staffForgotPassword(new ForgotPasswordRequest("meera.krishnan@navix.example", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("TOO_MANY_ATTEMPTS");
    }

    /** PLAN Phase 8: an app sign-in (it names a device) gets a short access token bound to the device and a refresh token. */
    @Test
    void borrowerLogin_withADeviceIssuesAShortAccessTokenAndARefreshToken() {
        when(otpService.verify("9819000001", "123456", OtpVerifierPort.LOGIN)).thenReturn(true);
        when(mobileRepository.findByMobile("9819000001")).thenReturn(Optional.of(claim(9000001L, "9819000001")));
        java.time.Instant until = java.time.Instant.now().plus(java.time.Duration.ofDays(30));
        when(refreshTokens.issue(9000001L, "device-0001")).thenReturn(new RefreshTokenService.Issued("rt-1", until));

        var resp = controller.borrowerLogin(new AuthDtos.BorrowerLoginRequest("9819000001", "123456", "Asha",
                "device-0001", "Pixel 6", "1.0.0")).getData();

        assertThat(resp.refreshToken()).isEqualTo("rt-1");
        assertThat(resp.expiresIn()).isEqualTo(900L);
        assertThat(resp.refreshExpiresAt()).isEqualTo(until);
        JwtService.Principal p = jwt.verify(resp.token());
        assertThat(p.deviceId()).isEqualTo("device-0001");
        assertThat(p.audience()).isEqualTo(JwtService.AUDIENCE_BORROWER);
        verify(devices).touch(9000001L, "device-0001", "Pixel 6", null, "1.0.0");
    }

    /** The web (no device id) keeps its single token: nothing for it to refresh. */
    @Test
    void borrowerLogin_withoutADeviceKeepsTheWebToken() {
        when(otpService.verify("9819000001", "123456", OtpVerifierPort.LOGIN)).thenReturn(true);
        when(mobileRepository.findByMobile("9819000001")).thenReturn(Optional.of(claim(9000001L, "9819000001")));

        var resp = controller.borrowerLogin(new BorrowerLoginRequest("9819000001", "123456", null)).getData();

        assertThat(resp.refreshToken()).isNull();
        assertThat(jwt.verify(resp.token()).deviceId()).isNull();
        verify(refreshTokens, never()).issue(anyLong(), any());
    }

    @Test
    void borrowerRefresh_rotatesAndKeepsTheDevice() {
        when(refreshTokens.rotate("rt-1", "device-0001")).thenReturn(new RefreshTokenService.Rotated(9000001L,
                "device-0001", new RefreshTokenService.Issued("rt-2", java.time.Instant.now().plusSeconds(60))));
        when(mobileRepository.findById(9000001L)).thenReturn(Optional.of(claim(9000001L, "9819000001")));

        var resp = controller.borrowerRefresh(new AuthDtos.RefreshRequest("rt-1", "device-0001")).getData();

        assertThat(resp.refreshToken()).isEqualTo("rt-2");
        assertThat(jwt.verify(resp.token()).id()).isEqualTo("9000001");
        assertThat(jwt.verify(resp.token()).deviceId()).isEqualTo("device-0001");
    }

    /** CC-38: logout ends this device's session; allDevices (signed in) ends them all; it never fails. */
    @Test
    void borrowerLogout_endsThisDeviceOrAllOfThem() {
        when(refreshTokens.revoke("rt-1")).thenReturn(new RefreshTokenService.Rotated(9000001L, "device-0001", null));
        controller.borrowerLogout(new AuthDtos.LogoutRequest("rt-1", null));
        verify(devices).remove(9000001L, "device-0001");

        controller.borrowerLogout(null);
        assertThatThrownBy(() -> controller.borrowerLogout(new AuthDtos.LogoutRequest(null, true)))
                .isInstanceOf(BusinessException.class);

        ActorContext.set(new CurrentActor("9000001", "Asha", "BORROWER", null));
        controller.borrowerLogout(new AuthDtos.LogoutRequest(null, true));
        verify(refreshTokens).revokeAll(9000001L);
        verify(devices).removeAll(9000001L);
    }

    /** CC-36: OTP sends are also capped per network address and per app install, not only per number. */
    @Test
    void cc36_otpRequestsAreCappedPerIpAndPerDevice() {
        when(otpService.request(any(), any())).thenReturn(new OtpVerifierPort.OtpRequestResult(true, null, 300));
        var http = new org.springframework.mock.web.MockHttpServletRequest();
        http.setRemoteAddr("10.0.0.1");
        for (int i = 0; i < 10; i++) {
            controller.requestBorrowerOtp(new AuthDtos.OtpRequestRequest("98190000" + (10 + i), "device-0001"), http);
        }
        assertThatThrownBy(() -> controller.requestBorrowerOtp(
                new AuthDtos.OtpRequestRequest("9819000099", "device-0001"), http))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("TOO_MANY_ATTEMPTS");
        // Another device on the same network still gets through, until the per-IP cap.
        for (int i = 0; i < 19; i++) { // the per-IP count is at 11; 30 is the cap
            controller.requestBorrowerOtp(new AuthDtos.OtpRequestRequest("98191000" + (10 + i), "device-" + (1000 + i)), http);
        }
        assertThatThrownBy(() -> controller.requestBorrowerOtp(
                new AuthDtos.OtpRequestRequest("9819200001", "device-9999"), http))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("TOO_MANY_ATTEMPTS");
    }

    @Test
    void clientIpIsTheLastForwardedHop() {
        var http = new org.springframework.mock.web.MockHttpServletRequest();
        http.setRemoteAddr("10.0.0.9");
        assertThat(AuthController.clientIp(http)).isEqualTo("10.0.0.9");
        http.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.7");
        assertThat(AuthController.clientIp(http)).isEqualTo("203.0.113.7");
    }
}
