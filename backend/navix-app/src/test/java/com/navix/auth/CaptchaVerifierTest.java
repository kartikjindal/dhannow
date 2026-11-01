package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The branch that matters is "is this thing switched on" — everything downstream of it is one HTTP
 * call to Cloudflare, which is not worth a stubbed server. What must not regress is that an
 * unconfigured deployment stays wide open (dev, CI, e2e, the demo seed all run that way) and a
 * configured one refuses an empty token without a round-trip.
 */
class CaptchaVerifierTest {

    @Test
    void unconfiguredIsANoOp() {
        CaptchaVerifier verifier = new CaptchaVerifier("", "");
        assertThat(verifier.isEnabled()).isFalse();
        assertThatCode(() -> verifier.verify(null, "staff-login")).doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify("anything", "staff-login")).doesNotThrowAnyException();
    }

    @Test
    void blankSecretIsTreatedAsUnset() {
        assertThat(new CaptchaVerifier("   ", "").isEnabled()).isFalse();
        assertThat(new CaptchaVerifier(null, null).isEnabled()).isFalse();
    }

    @Test
    void configuredRejectsAMissingToken() {
        CaptchaVerifier verifier = new CaptchaVerifier("a-secret", "");
        assertThat(verifier.isEnabled()).isTrue();
        for (String token : new String[] {null, "", "   ", "x".repeat(2049)}) {
            assertThatThrownBy(() -> verifier.verify(token, "staff-login"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "CAPTCHA_FAILED");
        }
    }

    // ---- PLAN Phase 8: the Android branch ----

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void androidRequest(String integrityToken) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader(CaptchaVerifier.CLIENT_HEADER, "android");
        if (integrityToken != null) {
            r.addHeader(CaptchaVerifier.INTEGRITY_HEADER, integrityToken);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));
    }

    private static FeatureFlagService flag(boolean on) {
        FeatureFlagService flags = Mockito.mock(FeatureFlagService.class);
        Mockito.when(flags.isEnabled(CaptchaVerifier.PLAY_INTEGRITY_FLAG, false)).thenReturn(on);
        return flags;
    }

    /** Flag ON: the integrity token replaces Turnstile, so a good token passes with no captcha token at all. */
    @Test
    void integrityOn_aPassingTokenReplacesTurnstile() {
        CaptchaVerifier verifier = new CaptchaVerifier("a-secret", "", "good"::equals, flag(true), false);
        androidRequest("good");
        assertThat(verifier.androidCheck()).isEqualTo(CaptchaVerifier.AndroidCheck.PASSED);
        assertThatCode(() -> verifier.verify(null, "borrower-password-login")).doesNotThrowAnyException();
    }

    @Test
    void integrityOn_aFailingOrMissingTokenIsRefused() {
        CaptchaVerifier verifier = new CaptchaVerifier("", "", "good"::equals, flag(true), false);
        for (String token : new String[] {"forged", null}) {
            androidRequest(token);
            assertThat(verifier.androidCheck()).isEqualTo(CaptchaVerifier.AndroidCheck.FAILED);
            assertThatThrownBy(() -> verifier.verify(null, "borrower-password-login"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INTEGRITY_FAILED");
        }
    }

    /**
     * Flag OFF: the header alone buys nothing. The request is judged by Turnstile like the web (refused here
     * without a captcha token), unless the opt-in config says otherwise.
     */
    @Test
    void integrityOff_theAndroidHeaderAloneDoesNotSkipTurnstile() {
        IntegrityVerifier never = t -> {
            throw new AssertionError("must not be called while the flag is off");
        };
        androidRequest("good");
        CaptchaVerifier safe = new CaptchaVerifier("a-secret", "", never, flag(false), false);
        assertThat(safe.androidCheck()).isEqualTo(CaptchaVerifier.AndroidCheck.OFF);
        assertThatThrownBy(() -> safe.verify(null, "borrower-password-login"))
                .hasFieldOrPropertyWithValue("code", "CAPTCHA_FAILED");

        CaptchaVerifier optedIn = new CaptchaVerifier("a-secret", "", never, flag(false), true);
        assertThatCode(() -> optedIn.verify(null, "borrower-password-login")).doesNotThrowAnyException();
    }

    @Test
    void aWebRequestNeverTouchesIntegrity() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        CaptchaVerifier verifier = new CaptchaVerifier("", "", t -> false, flag(true), false);
        assertThat(verifier.androidCheck()).isEqualTo(CaptchaVerifier.AndroidCheck.NOT_ANDROID);
        assertThatCode(() -> verifier.verify(null, "staff-login")).doesNotThrowAnyException();
    }

    /** The verdict rule: our package, Play-recognised, device integrity, and fresh. */
    @Test
    void playIntegrityVerdictRule() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        java.time.Instant now = java.time.Instant.parse("2026-11-01T10:00:00Z");
        String good = """
                {"requestDetails":{"requestPackageName":"com.dhannow.app","timestampMillis":%d},
                 "appIntegrity":{"appRecognitionVerdict":"PLAY_RECOGNIZED","packageName":"com.dhannow.app"},
                 "deviceIntegrity":{"deviceRecognitionVerdict":["MEETS_BASIC_INTEGRITY","MEETS_DEVICE_INTEGRITY"]}}
                """.formatted(now.minusSeconds(30).toEpochMilli());
        assertThat(PlayIntegrityClient.judge(json.readTree(good), "com.dhannow.app", now)).isTrue();
        assertThat(PlayIntegrityClient.judge(json.readTree(good), "com.other.app", now)).isFalse();
        assertThat(PlayIntegrityClient.judge(json.readTree(good.replace("PLAY_RECOGNIZED", "UNRECOGNIZED_VERSION")),
                "com.dhannow.app", now)).isFalse();
        assertThat(PlayIntegrityClient.judge(json.readTree(good.replace("\"MEETS_DEVICE_INTEGRITY\"", "\"X\"")),
                "com.dhannow.app", now)).isFalse(); // CC-43: rooted / emulator
        assertThat(PlayIntegrityClient.judge(json.readTree(good), "com.dhannow.app", now.plusSeconds(600))).isFalse();
        assertThat(PlayIntegrityClient.judge(null, "com.dhannow.app", now)).isFalse();
    }
}
