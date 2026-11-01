package com.navix.auth;

import jakarta.validation.constraints.NotBlank;

/** Request/response records for the real auth endpoints (P6). */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * {@code force} = "sign me in here and end the other session" — sent only after the caller has
     * seen a {@code SESSION_CONFLICT} and the user picked "Continue here". A boxed {@code Boolean}
     * (not primitive) so an older client that omits the field is treated as {@code false}, not a
     * bind error.
     *
     * <p>{@code captchaToken} is the Cloudflare Turnstile response. Deliberately un-annotated for the
     * same reason as {@code force}: whether a missing token matters is
     * {@link CaptchaVerifier}'s call (it is unconfigured, and so ignored, in dev/CI), not bean
     * validation's.
     */
    public record StaffLoginRequest(@NotBlank String email, @NotBlank String password, Boolean force,
                                    String captchaToken, String totpCode) {
        public StaffLoginRequest(String email, String password, Boolean force, String captchaToken) {
            this(email, password, force, captchaToken, null);
        }
    }

    /**
     * Staff MFA enrolment (PLAN Phase 7) happens before the first MFA sign-in, so it re-checks the password instead
     * of a session. {@code code} is only read by confirm.
     */
    public record MfaEnrolRequest(@NotBlank String email, @NotBlank String password, String captchaToken,
                                  String code) {
    }

    /** The secret to type or scan into an authenticator app, shown once. */
    public record MfaEnrolResponse(String secret, String otpauthUri) {
    }

    /**
     * Borrower login: mobile + the OTP delivered by SMS (UltronSMS gateway).
     *
     * <p>There is deliberately NO {@code customerId} here — the identity is derived server-side from
     * the OTP-verified mobile. A client-supplied id was trusted verbatim, so anyone who could verify
     * an OTP on their own handset could mint a token for any other customer.
     */
    public record BorrowerLoginRequest(@NotBlank String mobile, @NotBlank String otp, String name,
                                       String deviceId, String deviceName, String appVersion) {
        public BorrowerLoginRequest(String mobile, String otp, String name) {
            this(mobile, otp, name, null, null, null);
        }
    }

    /**
     * Request an OTP be generated + SMS-delivered to {@code mobile}. {@code deviceId} (the app's install id) adds a
     * per-device send limit (CC-36); the web sends none.
     */
    public record OtpRequestRequest(@NotBlank String mobile, String deviceId) {
        public OtpRequestRequest(String mobile) {
            this(mobile, null);
        }
    }

    /** OTP-request outcome: whether the SMS went out + ttl. {@code devCode} is set only when
     *  {@code navix.sms.dev-echo=true} (local testing without a handset). */
    public record OtpRequestResponse(boolean sent, int ttlSeconds, String devCode) {
    }

    /**
     * Issued token + the identity it carries (the BFF stores the token in an httpOnly cookie).
     *
     * <p>PLAN Phase 8: an app sign-in (one that names a {@code deviceId}) also gets a {@code refreshToken} (30 days,
     * rotated on every use) and a short access {@code token} that lasts {@code expiresIn} seconds (15 minutes).
     * The web sends no device id and keeps its single long-lived token; those three fields are then null.
     */
    public record AuthResponse(String token, String id, String name, String role, Long customerId,
                               String refreshToken, Long expiresIn, java.time.Instant refreshExpiresAt) {
        public AuthResponse(String token, String id, String name, String role, Long customerId) {
            this(token, id, name, role, customerId, null, null, null);
        }

        /** The customer id as the borrower sees it ({@code DN0000001}, CC-24). */
        @com.fasterxml.jackson.annotation.JsonProperty("customerCode")
        public String customerCode() {
            return com.navix.common.util.CustomerIds.code(customerId);
        }
    }

    /** Spend a refresh token for a new access + refresh pair. It is bound to the device it was issued to. */
    public record RefreshRequest(@NotBlank String refreshToken, @NotBlank String deviceId) {
    }

    /**
     * Sign out. {@code refreshToken} ends this device's session; {@code allDevices=true} (with a borrower bearer)
     * ends every session of the account (CC-38).
     */
    public record LogoutRequest(String refreshToken, Boolean allDevices) {
    }

    /** Borrower sign-in by password (the OTP-less alternative). {@code captchaToken}: see
     *  {@link StaffLoginRequest}. */
    public record BorrowerPasswordLoginRequest(@NotBlank String mobile, @NotBlank String password,
                                               String captchaToken, String deviceId, String deviceName,
                                               String appVersion) {
        public BorrowerPasswordLoginRequest(String mobile, String password, String captchaToken) {
            this(mobile, password, captchaToken, null, null, null);
        }
    }

    /** Set/replace a password for the signed-in borrower (optional signup step / profile). */
    public record SetPasswordRequest(@NotBlank String password) {
    }

    /** Forgot-password: the email that must match an account before a reset link is sent — the link
     *  only ever goes to that inbox, so owning it is the proof; no mobile is asked.
     *  {@code captchaToken}: see {@link StaffLoginRequest}. */
    public record ForgotPasswordRequest(@NotBlank String email, String captchaToken) {
    }

    /** Reset-password landing: the one-time token from the email link + the chosen new password. */
    public record ResetPasswordRequest(@NotBlank String token, @NotBlank String password) {
    }

    /**
     * Staff invite activation: the one-time token from the invite email, the invitee's display name,
     * the password they choose, and an optional contact mobile (enables their own forgot-password).
     */
    public record AcceptInviteRequest(@NotBlank String token, @NotBlank String name,
                                      @NotBlank String password, String mobile) {
    }

    /** A generic, non-enumerating acknowledgement (forgot/reset/set responses). */
    public record MessageResponse(String message) {
    }
}
