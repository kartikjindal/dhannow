package com.navix.auth;

import com.navix.auth.AuthDtos.AcceptInviteRequest;
import com.navix.auth.AuthDtos.AuthResponse;
import com.navix.auth.AuthDtos.BorrowerLoginRequest;
import com.navix.auth.AuthDtos.BorrowerPasswordLoginRequest;
import com.navix.auth.AuthDtos.ForgotPasswordRequest;
import com.navix.auth.AuthDtos.LogoutRequest;
import com.navix.auth.AuthDtos.RefreshRequest;
import com.navix.auth.AuthDtos.MfaEnrolRequest;
import com.navix.auth.AuthDtos.MfaEnrolResponse;
import com.navix.auth.AuthDtos.MessageResponse;
import com.navix.auth.AuthDtos.OtpRequestRequest;
import com.navix.auth.AuthDtos.OtpRequestResponse;
import com.navix.auth.AuthDtos.OtpPasswordResetRequest;
import com.navix.auth.AuthDtos.ResetPasswordRequest;
import com.navix.auth.AuthDtos.SetPasswordRequest;
import com.navix.auth.AuthDtos.StaffLoginRequest;
import com.navix.common.exception.BusinessException;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.common.security.ActorContext;
import com.navix.common.security.JwtService;
import com.navix.common.util.Masking;
import com.navix.common.web.ApiResponse;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import com.navix.iam.service.InviteService;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.repository.CustomerProfileRepository;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Real auth (P6): issues HS256 JWTs for the staff and borrower namespaces. Login is
 * moved out of the Next.js BFF into the backend; the BFF now calls these, stores the
 * returned token in its httpOnly cookie, and forwards it as a bearer.
 *
 * <p>Staff login is BCrypt vs {@code staff_user.password_hash}. Borrower login verifies a
 * real SMS-delivered OTP ({@link OtpService} → UltronSMS gateway) — request the code via
 * {@code /borrower/otp/request} first, then {@code /borrower/login}.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final StaffUserRepository staffRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final BorrowerOtpService otpService;
    private final CustomerProfileRepository profileRepository;
    private final BorrowerCredentialRepository credentialRepository;
    private final PasswordResetService passwordResetService;
    private final InviteService inviteService;
    private final BorrowerMobileRepository mobileRepository;
    private final AttemptLimiter limiter;
    private final CaptchaVerifier captcha;
    private final com.navix.config.StaffSessionRegistry staffSessionRegistry;
    private final StaffMfaService mfa;
    private final RefreshTokenService refreshTokens;
    private final DeviceService devices;
    /** PLAN Phase 8: an app session's access token lifetime (the refresh token renews it). Field-injected, see below. */
    @org.springframework.beans.factory.annotation.Value("${navix.auth.borrower-access-ttl-seconds:900}")
    private long accessTtlSeconds = 900;
    /**
     * Same TTL {@link JwtService} issues staff tokens with — used to decide whether a stored
     * {@code active_session_id} could still back a live token (see {@link #staffLogin}).
     *
     * <p>Deliberately NOT {@code final}: {@code @RequiredArgsConstructor} would then put it in the
     * constructor, where Spring resolves parameters by TYPE and asks for "a bean of type long" —
     * which does not exist, so every context in the app fails to start. Field injection is the
     * boring way to hand a primitive to a Lombok-constructed bean.
     */
    @org.springframework.beans.factory.annotation.Value("${navix.auth.ttl-seconds:86400}")
    private long staffTtlSeconds;

    /**
     * Turnstile {@code action} per surface — must match the {@code action} the widget is rendered
     * with on the matching page. This is what stops a token farmed on the (unauthenticated,
     * low-friction) forgot-password form being spent against staff sign-in.
     */
    private static final String ACTION_STAFF_LOGIN = "staff-login";
    private static final String ACTION_BORROWER_LOGIN = "borrower-password-login";
    private static final String ACTION_BORROWER_FORGOT = "borrower-forgot-password";
    private static final String ACTION_STAFF_FORGOT = "staff-forgot-password";

    /**
     * Sign-in attempts allowed per identifier per {@link #LOGIN_WINDOW} (either audience).
     *
     * <p>Three wrong passwords, then a 30-second cool-off, then three more — rather than the previous
     * 10-per-15-min lock-out, where one bad afternoon shut a staff member out for a quarter of an
     * hour. The count is remembered for {@link #LOGIN_WINDOW} so slow guessing is caught too; only
     * the WAIT is short.
     *
     * <p>Only WRONG passwords count — {@link AttemptLimiter#clear} runs on success. Counting
     * successes at a cap of three locks out anyone who signs in on two devices in a row.
     *
     * <p>Trade-off, stated plainly: this raises the ceiling on an online password-guessing attack
     * from ~40 to ~360 attempts an hour per account. It is a throttle, not the defence — the
     * password policy and BCrypt are.
     */
    private static final int MAX_LOGIN_ATTEMPTS = 3;
    /**
     * How long wrong passwords are remembered. Must be much longer than {@link #LOGIN_COOLOFF}:
     * when both were 30s the limiter never fired against anyone typing by hand, because each
     * attempt more than 30s after the last started a fresh count.
     */
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    /** How long the borrower/staff waits once they have used up the three. */
    private static final Duration LOGIN_COOLOFF = Duration.ofSeconds(30);
    /** Lead sentence only — {@link AttemptLimiter} appends the real remaining wait. */
    private static final String TOO_MANY_LOGINS = "Too many sign-in attempts.";

    /**
     * Forgot-password requests allowed per email per {@link #RESET_WINDOW} (either audience). Every
     * request counts, match or not — that's what keeps the limiter from revealing which emails have
     * an account. Unlike the login limiter, the count window and the block-for duration are the same
     * fifteen minutes: a reset link is a slow, deliberate action (open the email, click through), not
     * something worth a short cool-off and a quick retry.
     */
    private static final int MAX_RESET_REQUESTS = 3;
    private static final Duration RESET_WINDOW = Duration.ofMinutes(15);
    /** Lead sentence only — {@link AttemptLimiter} appends the real remaining wait. */
    private static final String TOO_MANY_RESETS = "Too many reset requests.";

    @PostMapping("/staff/login")
    public ApiResponse<AuthResponse> staffLogin(@Valid @RequestBody StaffLoginRequest req) {
        StaffUser staff = authenticateStaff(req.email(), req.password(), req.captchaToken());
        String maskedEmail = Masking.maskEmail(req.email().trim());
        // PLAN Phase 7: the TOTP step. A missing or wrong code still counts against the three attempts (the
        // limiter is only cleared below), so the code cannot be guessed by someone who has the password.
        try {
            mfa.checkAtLogin(staff.getId(), staff.getRole().name(), req.totpCode());
        } catch (BusinessException e) {
            log.warn("staff login failed reason={} staffId={} email={}", e.getCode(), staff.getId(), maskedEmail);
            throw e;
        }
        // Right password (and code): the three strikes are three WRONG ones, so wipe the slate.
        limiter.clear(staffLimitKey(req.email()));

        // One live console session per staffer, ALL roles including ADMIN. A second sign-in while a
        // session is live is refused with a code the UI can distinguish (SESSION_CONFLICT), so the
        // operator gets an explicit "continue there / continue here" choice instead of silently
        // kicking their other tab. `force` is that choice, sent only after the UI has shown it.
        boolean force = Boolean.TRUE.equals(req.force());
        // active_session_id is cleared ONLY by a matching-sid logout; a closed browser, a cleared
        // cookie, or an expired token leaves it set forever, so a stale row would show this dialog
        // to a staffer for the rest of time (and to a freshly-invited one on their very first
        // sign-in, since accept-invite mints a sid). A token that could no longer be valid can't be a
        // live session, so treat the stamp as stale once it's older than the token's own TTL.
        boolean sessionLive = staff.getActiveSessionId() != null
                && staff.getActiveSessionAt() != null
                && staff.getActiveSessionAt().isAfter(
                        java.time.Instant.now().minusSeconds(staffTtlSeconds));
        if (sessionLive && !force) {
            log.warn("staff login blocked reason=SESSION_CONFLICT staffId={} email={}", staff.getId(), maskedEmail);
            throw new BusinessException("SESSION_CONFLICT",
                    "You're already signed in on another device or browser.");
        }
        String sessionId = java.util.UUID.randomUUID().toString();
        staff.setActiveSessionId(sessionId);
        staff.setActiveSessionAt(java.time.Instant.now());
        staffRepository.save(staff);
        staffSessionRegistry.invalidate(staff.getId());

        String id = String.valueOf(staff.getId());
        String token = jwtService.issue(id, staff.getName(), staff.getRole().name(),
                JwtService.AUDIENCE_STAFF, sessionId);
        log.info("staff login ok staffId={} role={} forced={}", id, staff.getRole(), force);
        return ApiResponse.ok(new AuthResponse(token, id, staff.getName(), staff.getRole().name(), null));
    }

    /**
     * PLAN Phase 7: start setting up the authenticator app (password re-checked, same limiter as sign-in). The
     * secret is shown once; a staff member already enrolled must ask an ADMIN to reset it.
     */
    @PostMapping("/staff/mfa/enrol")
    public ApiResponse<MfaEnrolResponse> staffMfaEnrol(@Valid @RequestBody MfaEnrolRequest req) {
        StaffUser staff = authenticateStaff(req.email(), req.password(), req.captchaToken());
        StaffMfaService.Enrolment e = mfa.enrol(staff.getId(), staff.getEmail());
        limiter.clear(staffLimitKey(req.email()));
        log.info("staff mfa enrolment started staffId={}", staff.getId());
        return ApiResponse.ok(new MfaEnrolResponse(e.secret(), e.otpauthUri()));
    }

    /** The first code from the app confirms the enrolment; the next sign-in needs a code. */
    @PostMapping("/staff/mfa/confirm")
    public ApiResponse<MessageResponse> staffMfaConfirm(@Valid @RequestBody MfaEnrolRequest req) {
        StaffUser staff = authenticateStaff(req.email(), req.password(), req.captchaToken());
        mfa.confirm(staff.getId(), req.code());
        limiter.clear(staffLimitKey(req.email()));
        log.info("staff mfa enrolment confirmed staffId={}", staff.getId());
        return ApiResponse.ok(new MessageResponse("Two-step sign-in is on."));
    }

    /**
     * Captcha, limiter, lookup, status and password: everything a staff sign-in checks before MFA. The limiter is
     * hit here and cleared by the caller only once the whole step succeeded.
     */
    private StaffUser authenticateStaff(String email, String password, String captchaToken) {
        // Ahead of the limiter on purpose: a bot that cannot answer the challenge must not be able
        // to spend a real staffer's three attempts and hold them out of the console.
        captcha.verify(captchaToken, ACTION_STAFF_LOGIN);
        String maskedEmail = Masking.maskEmail(email == null ? null : email.trim());
        // Before the lookup AND before BCrypt — otherwise the endpoint is an unbounded password oracle.
        limiter.hit(staffLimitKey(email), MAX_LOGIN_ATTEMPTS, LOGIN_WINDOW, LOGIN_COOLOFF, TOO_MANY_LOGINS);
        StaffUser staff = staffRepository.findByEmail(email.trim()).orElse(null);
        if (staff == null) {
            log.warn("staff login failed reason=INVALID_CREDENTIALS email={}", maskedEmail);
            throw new BusinessException("INVALID_CREDENTIALS", "Invalid email or password");
        }
        if (staff.getStatus() != StaffStatus.ACTIVE) {
            log.warn("staff login failed reason=INACTIVE_STAFF staffId={} email={}", staff.getId(), maskedEmail);
            throw new BusinessException("INACTIVE_STAFF", "This staff account is not active");
        }
        if (staff.getPasswordHash() == null
                || !passwordEncoder.matches(password, staff.getPasswordHash())) {
            log.warn("staff login failed reason=INVALID_CREDENTIALS staffId={} email={}", staff.getId(), maskedEmail);
            throw new BusinessException("INVALID_CREDENTIALS", "Invalid email or password");
        }
        return staff;
    }

    private static String staffLimitKey(String email) {
        return "staff:" + email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Clear the caller's own live session so a clean sign-out doesn't force the NEXT login to pass
     * {@code force}. Only clears when the token's OWN {@code sid} matches the stored one — otherwise
     * a superseded session's stale logout (fired after being replaced) would wipe the session that
     * replaced it. Public route ({@code /api/auth/**}), but idempotent/no-op without a valid staff
     * bearer, so it never needs to be treated as a protected endpoint.
     */
    @PostMapping("/staff/logout")
    public ApiResponse<MessageResponse> staffLogout(jakarta.servlet.http.HttpServletRequest request) {
        String actorId = com.navix.common.security.ActorContext.get().id();
        String actorRole = com.navix.common.security.ActorContext.get().role();
        if (actorId == null || "BORROWER".equals(actorRole)) {
            return ApiResponse.ok(new MessageResponse("Signed out."));
        }
        String tokenSessionId = (String) request.getAttribute(
                com.navix.config.JwtAuthFilter.STAFF_SESSION_ID_ATTR);
        try {
            Long id = Long.valueOf(actorId);
            staffRepository.findById(id).ifPresent(s -> {
                if (tokenSessionId != null && tokenSessionId.equals(s.getActiveSessionId())) {
                    s.setActiveSessionId(null);
                    s.setActiveSessionAt(null);
                    staffRepository.save(s);
                    staffSessionRegistry.invalidate(id);
                }
            });
        } catch (NumberFormatException ignored) {
            // Non-numeric actor id — nothing to clear.
        }
        return ApiResponse.ok(new MessageResponse("Signed out."));
    }

    /**
     * Activate a staff account from a one-time invite token and set the chosen password. Public (an
     * invitee has no session yet) — the token is the credential. On success the staffer is signed in
     * immediately (a staff JWT is issued, mirroring {@code /staff/login}) so they land in the console.
     */
    @PostMapping("/staff/accept-invite")
    public ApiResponse<AuthResponse> staffAcceptInvite(@Valid @RequestBody AcceptInviteRequest req) {
        PasswordPolicy.validate(req.password());
        String passwordHash = passwordEncoder.encode(req.password());
        StaffResponse staff = inviteService.acceptInvite(req.token(), req.name().trim(), passwordHash, req.mobile());
        String id = String.valueOf(staff.id());
        // Mint a session here too — otherwise a freshly-activated staffer holds a sid-less token,
        // which the single-session filter grandfathers as "always current" (see StaffSessionRegistry),
        // exempting the very first session from the enforcement everyone else gets.
        String sessionId = java.util.UUID.randomUUID().toString();
        staffRepository.findById(staff.id()).ifPresent(s -> {
            s.setActiveSessionId(sessionId);
            s.setActiveSessionAt(java.time.Instant.now());
            staffRepository.save(s);
        });
        String token = jwtService.issue(id, staff.name(), staff.role().name(),
                JwtService.AUDIENCE_STAFF, sessionId);
        log.info("staff invite accepted staffId={} role={}", id, staff.role());
        return ApiResponse.ok(new AuthResponse(token, id, staff.name(), staff.role().name(), null));
    }

    /**
     * OTP sends per network address / per app install per {@link #OTP_SOURCE_WINDOW}, on top of the per-mobile cap
     * in {@link BorrowerOtpService} (CC-36: SMS pumping spreads over many numbers from one source). Generous per
     * IP because Indian mobile carriers put many users behind one address. An app request whose Play Integrity
     * check failed (no Play Services, CC-42, or a failed verdict) may still use OTP, under the strict caps.
     */
    private static final int OTP_PER_IP = 30;
    private static final int OTP_PER_DEVICE = 10;
    private static final int OTP_PER_IP_STRICT = 10;
    private static final int OTP_PER_DEVICE_STRICT = 3;
    private static final Duration OTP_SOURCE_WINDOW = Duration.ofHours(1);
    /** App password sign-ins per source per hour (success or not), the price of skipping Turnstile — see CaptchaVerifier. */
    private static final int PW_PER_IP = 30;
    private static final int PW_PER_DEVICE = 10;
    private static final String TOO_MANY_OTPS = "Too many OTP requests.";
    /** Mock SMS (demo, e2e) sends nothing, so there is no SMS spend for the per-source caps to protect. */
    @org.springframework.beans.factory.annotation.Value("${navix.sms.mock:false}")
    private boolean smsMock;

    /** Generate an OTP and SMS it to the borrower's mobile (UltronSMS). Call before login. */
    @PostMapping("/borrower/otp/request")
    public ApiResponse<OtpRequestResponse> requestBorrowerOtp(@Valid @RequestBody OtpRequestRequest req,
                                                              jakarta.servlet.http.HttpServletRequest http) {
        capOtpSource(req, http);
        OtpVerifierPort.OtpRequestResult result = otpService.request(req.mobile(), OtpVerifierPort.LOGIN);
        return ApiResponse.ok(new OtpRequestResponse(result.sent(), result.ttlSeconds(), result.devCode()));
    }

    /**
     * PLAN Phase 11 (S5): forgot password in the app. The same answer for any number, but only a registered one
     * gets a code; nothing is created for an unknown number (OTP sign-in would register it). Same caps as a sign-in
     * OTP, and the per-number send cap counts either way.
     */
    @PostMapping("/borrower/forgot-password/otp")
    public ApiResponse<OtpRequestResponse> forgotPasswordOtp(@Valid @RequestBody OtpRequestRequest req,
                                                             jakarta.servlet.http.HttpServletRequest http) {
        capOtpSource(req, http);
        OtpVerifierPort.OtpRequestResult result = otpService.request(req.mobile(), OtpVerifierPort.PASSWORD_RESET,
                registeredOrNull(req.mobile()) != null);
        return ApiResponse.ok(new OtpRequestResponse(result.sent(), result.ttlSeconds(), result.devCode()));
    }

    /**
     * PLAN Phase 11 (S5): spend the reset code and set the new password; the borrower is then signed in. An unknown
     * number is refused exactly like a wrong code ({@code INVALID_OTP}). The policy is checked first so a weak
     * password does not burn the code.
     */
    @PostMapping("/borrower/forgot-password/reset")
    public ApiResponse<AuthResponse> resetPasswordWithOtp(@Valid @RequestBody OtpPasswordResetRequest req) {
        PasswordPolicy.validate(req.password());
        Long customerId = registeredOrNull(req.mobile());
        // Unknown number first: mock SMS accepts the fixed code for any number.
        if (customerId == null || !otpService.verify(req.mobile(), req.otp(), OtpVerifierPort.PASSWORD_RESET)) {
            log.warn("borrower otp reset failed mobile={}", Masking.maskPhone(req.mobile()));
            throw new BusinessException("INVALID_OTP", "Invalid or expired OTP");
        }
        passwordResetService.setBorrowerPassword(customerId, req.password());
        limiter.clear("pw:" + req.mobile().replaceAll("\\D", "")); // the password-login limiter's key
        log.info("borrower password reset by otp customerId={}", customerId);
        return ApiResponse.ok(borrowerSession(customerId, resolveBorrowerName(null, req.mobile()), req.deviceId(),
                req.deviceName(), req.appVersion()));
    }

    /** A registered, open account for this number, or null. A closed account reads as unknown here (no leak). */
    private Long registeredOrNull(String mobile) {
        try {
            return existingCustomerId(mobile);
        } catch (BusinessException e) {
            if ("ACCOUNT_CLOSED".equals(e.getCode())) {
                return null;
            }
            throw e;
        }
    }

    /** The per-IP and per-device OTP send caps (CC-36); strict for an app whose integrity check failed. */
    private void capOtpSource(OtpRequestRequest req, jakarta.servlet.http.HttpServletRequest http) {
        boolean strict = captcha.androidCheck() == CaptchaVerifier.AndroidCheck.FAILED;
        // ponytail: the web BFF forwards no client address, so every web send shares the BFF's one IP cap. Fine
        // until Phase 10 removes the borrower web; forward X-Forwarded-For from the BFF if it ever stays.
        if (!smsMock) {
            limiter.hit("otp-ip:" + clientIp(http), strict ? OTP_PER_IP_STRICT : OTP_PER_IP,
                    OTP_SOURCE_WINDOW, OTP_SOURCE_WINDOW, TOO_MANY_OTPS);
            String device = req.deviceId() != null && !req.deviceId().isBlank()
                    ? req.deviceId() : http.getHeader("X-Device-Id");
            if (device != null && !device.isBlank()) {
                limiter.hit("otp-device:" + device.trim(), strict ? OTP_PER_DEVICE_STRICT : OTP_PER_DEVICE,
                        OTP_SOURCE_WINDOW, OTP_SOURCE_WINDOW, TOO_MANY_OTPS);
            }
        }
    }

    @PostMapping("/borrower/login")
    public ApiResponse<AuthResponse> borrowerLogin(@Valid @RequestBody BorrowerLoginRequest req) {
        if (!otpService.verify(req.mobile(), req.otp(), OtpVerifierPort.LOGIN)) {
            log.warn("borrower login failed reason=INVALID_OTP mobile={}", Masking.maskPhone(req.mobile()));
            throw new BusinessException("INVALID_OTP", "Invalid or expired OTP");
        }
        long customerId = claimCustomerId(req.mobile());
        String name = resolveBorrowerName(req.name(), req.mobile());
        log.info("borrower login ok customerId={} app={}", customerId, req.deviceId() != null);
        return ApiResponse.ok(borrowerSession(customerId, name, req.deviceId(), req.deviceName(), req.appVersion()));
    }

    /**
     * PLAN Phase 8: spend a refresh token for a fresh pair (CC-40, transparent to the borrower). A spent token sent
     * again revokes the whole sign-in ({@code REFRESH_REUSED}, CC-39); unknown, expired or another device's token is
     * {@code REFRESH_INVALID}. Either way the app signs in again.
     */
    @PostMapping("/borrower/refresh")
    public ApiResponse<AuthResponse> borrowerRefresh(@Valid @RequestBody RefreshRequest req) {
        RefreshTokenService.Rotated r = refreshTokens.rotate(req.refreshToken(), req.deviceId());
        String mobile = mobileRepository.findById(r.customerId()).map(BorrowerMobile::getMobile).orElse(null);
        devices.touch(r.customerId(), r.deviceId(), null, null, null);
        return ApiResponse.ok(appSession(r.customerId(), resolveBorrowerName(null, mobile), r.deviceId(), r.next()));
    }

    /**
     * Sign out: {@code refreshToken} ends this device's session and forgets its push token; {@code allDevices} with
     * a borrower bearer ends all of them (CC-38). Idempotent. The access token lapses on its own within 15 minutes.
     */
    @PostMapping("/borrower/logout")
    public ApiResponse<MessageResponse> borrowerLogout(@RequestBody(required = false) LogoutRequest req) {
        if (req != null && req.refreshToken() != null && !req.refreshToken().isBlank()) {
            RefreshTokenService.Rotated owner = refreshTokens.revoke(req.refreshToken());
            if (owner != null) {
                devices.remove(owner.customerId(), owner.deviceId());
            }
        }
        if (req != null && Boolean.TRUE.equals(req.allDevices())) {
            var actor = ActorContext.get();
            if (!"BORROWER".equals(actor.role())) {
                throw new BusinessException("UNAUTHORIZED", "Sign in to sign out of all devices");
            }
            long customerId = Long.parseLong(actor.id());
            refreshTokens.revokeAll(customerId);
            devices.removeAll(customerId);
            log.info("borrower signed out of all devices customerId={}", customerId);
        }
        return ApiResponse.ok(new MessageResponse("Signed out."));
    }

    /**
     * The token(s) for a borrower sign-in. Without a device id (the web BFF) it is one long-lived token as before;
     * the borrower web goes in Phase 10. With one (the app) it is a 15-minute access token plus a rotating refresh
     * token bound to that device, and the device is recorded for Settings > Devices.
     */
    private AuthResponse borrowerSession(long customerId, String name, String deviceId, String deviceName,
                                         String appVersion) {
        String id = String.valueOf(customerId);
        if (deviceId == null || deviceId.isBlank()) {
            String token = jwtService.issue(id, name, "BORROWER", JwtService.AUDIENCE_BORROWER);
            return new AuthResponse(token, id, name, "BORROWER", customerId);
        }
        String device = DeviceService.requireDeviceId(deviceId.trim());
        RefreshTokenService.Issued refresh = refreshTokens.issue(customerId, device);
        devices.touch(customerId, device, deviceName, null, appVersion);
        return appSession(customerId, name, device, refresh);
    }

    private AuthResponse appSession(long customerId, String name, String deviceId, RefreshTokenService.Issued refresh) {
        String id = String.valueOf(customerId);
        String token = jwtService.issueForDevice(id, name, "BORROWER", JwtService.AUDIENCE_BORROWER, deviceId,
                accessTtlSeconds);
        return new AuthResponse(token, id, name, "BORROWER", customerId, refresh.token(), accessTtlSeconds,
                refresh.expiresAt());
    }

    /**
     * The caller's address for the per-IP limit: the LAST {@code X-Forwarded-For} hop (the one the load balancer
     * appended, so a client cannot choose it), else the socket peer.
     */
    static String clientIp(jakarta.servlet.http.HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] hops = xff.split(",");
            return hops[hops.length - 1].trim();
        }
        return String.valueOf(http.getRemoteAddr());
    }

    /** Borrower sign-in by password (the OTP-less alternative; requires a previously set password). */
    @PostMapping("/borrower/password-login")
    public ApiResponse<AuthResponse> borrowerPasswordLogin(@Valid @RequestBody BorrowerPasswordLoginRequest req,
                                                           jakarta.servlet.http.HttpServletRequest http) {
        // PLAN Phase 11: an app request may skip Turnstile here while play-integrity is OFF (CaptchaVerifier), so every
        // app sign-in by password is capped by source: per IP (the ALB's hop) and per device id. Before the challenge,
        // so a flood never reaches Google either.
        if ("android".equalsIgnoreCase(http.getHeader(CaptchaVerifier.CLIENT_HEADER))) {
            limiter.hit("pw-ip:" + clientIp(http), PW_PER_IP, OTP_SOURCE_WINDOW, OTP_SOURCE_WINDOW, TOO_MANY_LOGINS);
            String device = req.deviceId() == null || req.deviceId().isBlank() ? "none" : req.deviceId().trim();
            limiter.hit("pw-device:" + device, PW_PER_DEVICE, OTP_SOURCE_WINDOW, OTP_SOURCE_WINDOW, TOO_MANY_LOGINS);
        }
        captcha.verify(req.captchaToken(), ACTION_BORROWER_LOGIN); // ahead of the limiter — see staffLogin
        String limitKey = "pw:" + req.mobile().replaceAll("\\D", "");
        limiter.hit(limitKey, MAX_LOGIN_ATTEMPTS, LOGIN_WINDOW, LOGIN_COOLOFF, TOO_MANY_LOGINS);
        // Read-only until the password matches: a number that has never signed in has no account, so nothing
        // is registered here before a credential check.
        Long existing = existingCustomerId(req.mobile());
        long customerId = existing == null ? -1L : existing;
        BorrowerCredential cred = existing == null ? null : credentialRepository.findById(customerId).orElse(null);
        if (cred == null) {
            log.warn("borrower password login failed reason=NO_PASSWORD_SET customerId={}", customerId);
            throw new BusinessException("NO_PASSWORD_SET",
                    "No password is set for this number. Sign in with an OTP, then add a password.");
        }
        if (!passwordEncoder.matches(req.password(), cred.getPasswordHash())) {
            log.warn("borrower password login failed reason=INVALID_CREDENTIALS customerId={}", customerId);
            throw new BusinessException("INVALID_CREDENTIALS", "Invalid mobile or password");
        }
        limiter.clear(limitKey); // as on staff login: only wrong passwords count toward the three
        String name = resolveBorrowerName(null, req.mobile());
        log.info("borrower password login ok customerId={}", customerId);
        return ApiResponse.ok(borrowerSession(customerId, name, req.deviceId(), req.deviceName(), req.appVersion()));
    }

    /** Set (or replace) a password for the signed-in borrower — the optional signup step / profile. */
    @PostMapping("/borrower/set-password")
    public ApiResponse<MessageResponse> setBorrowerPassword(@Valid @RequestBody SetPasswordRequest req) {
        var actor = ActorContext.get();
        if (!"BORROWER".equals(actor.role())) {
            throw new BusinessException("UNAUTHORIZED", "Sign in as a borrower to set a password");
        }
        passwordResetService.setBorrowerPassword(Long.parseLong(actor.id()), req.password());
        return ApiResponse.ok(new MessageResponse("Password set. You can now sign in with it."));
    }

    /** Borrower forgot-password — emails a reset link only when the email matches (no enumeration). */
    @PostMapping("/borrower/forgot-password")
    public ApiResponse<MessageResponse> borrowerForgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        captcha.verify(req.captchaToken(), ACTION_BORROWER_FORGOT);
        String email = req.email().trim();
        if (!email.isEmpty()) {
            limiter.hit("forgot:borrower:" + email.toLowerCase(Locale.ROOT),
                    MAX_RESET_REQUESTS, RESET_WINDOW, RESET_WINDOW, TOO_MANY_RESETS);
        }
        passwordResetService.requestBorrowerReset(email);
        return ApiResponse.ok(genericResetAck());
    }

    /** Borrower reset-password — redeem the one-time link token + set a new password. */
    @PostMapping("/borrower/reset-password")
    public ApiResponse<MessageResponse> borrowerResetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordResetService.resetPassword(req.token(), req.password(), PasswordResetService.BORROWER);
        return ApiResponse.ok(new MessageResponse("Password updated. You can now sign in."));
    }

    /** Staff forgot-password — emails a reset link only when the email matches (no enumeration). */
    @PostMapping("/staff/forgot-password")
    public ApiResponse<MessageResponse> staffForgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        captcha.verify(req.captchaToken(), ACTION_STAFF_FORGOT);
        String email = req.email().trim();
        if (!email.isEmpty()) {
            limiter.hit("forgot:staff:" + email.toLowerCase(Locale.ROOT),
                    MAX_RESET_REQUESTS, RESET_WINDOW, RESET_WINDOW, TOO_MANY_RESETS);
        }
        passwordResetService.requestStaffReset(email);
        return ApiResponse.ok(genericResetAck());
    }

    /** Staff reset-password — redeem the one-time link token + set a new password. */
    @PostMapping("/staff/reset-password")
    public ApiResponse<MessageResponse> staffResetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordResetService.resetPassword(req.token(), req.password(), PasswordResetService.STAFF);
        return ApiResponse.ok(new MessageResponse("Password updated. You can now sign in."));
    }

    /** The same acknowledgement whether or not the details matched — so neither leaks account existence. */
    private static MessageResponse genericResetAck() {
        return new MessageResponse("If that email matches an account, we've emailed a reset link.");
    }

    /**
     * The borrower's display name for the session. An explicit request name (set by a brand-new
     * signup, where the borrower just typed it) wins; otherwise a returning borrower's name is read
     * from their most recent stored KYC profile (by mobile). The returning-login screen deliberately
     * sends NO name, so the identity always comes from the server here — never from a name cached on
     * the device, which previously leaked one user's name to the next on a shared browser.
     */
    private String resolveBorrowerName(String reqName, String reqMobile) {
        if (reqName != null && !reqName.isBlank()) {
            return reqName.trim();
        }
        String mobile = reqMobile == null ? "" : reqMobile.replaceAll("\\D", "");
        if (!mobile.isEmpty()) {
            String stored = profileRepository.findFirstByMobileOrderByApplicationIdDesc(mobile)
                    .map(CustomerProfile::getFullName)
                    .filter(n -> n != null && !n.isBlank())
                    .orElse(null);
            if (stored != null) {
                return stored.trim();
            }
        }
        return "Borrower";
    }

    /**
     * The caller's customer id (CC-24): the one already registered to this mobile, or a new server-generated id
     * ({@code customer_id_seq}, shown as {@code DN0000001}) the first time the number signs in. The mobile is an
     * attribute of the account, never the source of its id, so two numbers can no longer land in one account.
     */
    private long claimCustomerId(String mobile) {
        String number = tenDigits(mobile);
        Long existing = existingCustomerId(mobile);
        if (existing != null) {
            return existing;
        }
        BorrowerMobile row = new BorrowerMobile();
        row.setCustomerId(mobileRepository.nextCustomerId());
        row.setMobile(number);
        try {
            return mobileRepository.saveAndFlush(row).getCustomerId();
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // The same number's first two sign-ins at once: the other one registered it.
            return mobileRepository.findByMobile(number).map(BorrowerMobile::getCustomerId).orElseThrow(() -> race);
        }
    }

    /** The customer id registered to this mobile, or null when the number has never signed in. Writes nothing. */
    private Long existingCustomerId(String mobile) {
        if (mobile == null || mobile.replaceAll("\\D", "").isEmpty()) {
            throw new BusinessException("INVALID_MOBILE", "Mobile must contain digits");
        }
        Long id = mobileRepository.findByMobile(tenDigits(mobile)).map(BorrowerMobile::getCustomerId).orElse(null);
        if (id != null && mobileRepository.deletionRequested(id)) {
            throw new BusinessException("ACCOUNT_CLOSED", "This account has been closed at your request.");
        }
        return id;
    }

    private static String tenDigits(String mobile) {
        String digits = mobile.replaceAll("\\D", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

}
