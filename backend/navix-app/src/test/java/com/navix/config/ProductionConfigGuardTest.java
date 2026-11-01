package com.navix.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.mock;

import com.navix.common.brand.LenderProperties;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.sms.SmsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The guard's whole value is that it trips, so each branch gets a check. Also pins the two
 * behaviours that keep the demo working: dev is never blocked, and a real prod config boots.
 */
class ProductionConfigGuardTest {

    private static final String DEFAULT_SECRET = "navix-local-dev-secret-change-me";
    private static final String STRONG_SECRET = "S6nQ2wYd8kLpR4vT7xZa1cE3gJ5mB9oU";  // 32 chars

    private static ProductionConfigGuard guard(String env, String secret, boolean mock, boolean devEcho) {
        return guard(env, secret, mock, devEcho, "signzy");
    }

    private static ProductionConfigGuard guard(String env, String secret, boolean mock, boolean devEcho,
                                               String esignProvider) {
        return guard(env, secret, mock, devEcho, esignProvider, LENDER);
    }

    private static final LenderProperties LENDER = new LenderProperties("Example Lender Private Limited",
            "1 Example Road, Gurugram 122001", "Registration EX-0001", "1800 000 000, care@lender.example");

    private static ProductionConfigGuard guard(String env, String secret, boolean mock, boolean devEcho,
                                               String esignProvider, LenderProperties lender) {
        return guard(env, secret, mock, devEcho, esignProvider, lender, MFA);
    }

    /** A valid key (32 zero bytes, base64) with enforcement on: what prod must have. */
    private static final MfaProperties MFA = new MfaProperties(true,
            java.util.Base64.getEncoder().encodeToString(new byte[32]), java.util.List.of("ADMIN"));

    private static ProductionConfigGuard guard(String env, String secret, boolean mock, boolean devEcho,
                                               String esignProvider, LenderProperties lender,
                                               MfaProperties mfa) {
        ProductionConfigGuard g = new ProductionConfigGuard(sms(mock, devEcho), flags(false), lender, mfa);
        ReflectionTestUtils.setField(g, "env", env);
        ReflectionTestUtils.setField(g, "secret", secret);
        ReflectionTestUtils.setField(g, "esignProvider", esignProvider);
        ReflectionTestUtils.setField(g, "digioProvider", "digio");
        return g;
    }

    /** The digio clause reads the {@code digio-mandate} flag; everything else ignores it. */
    private static FeatureFlagService flags(boolean digioMandate) {
        FeatureFlagService f = mock(FeatureFlagService.class);
        org.mockito.Mockito.lenient().when(f.isEnabled("digio-mandate", false)).thenReturn(digioMandate);
        return f;
    }

    private static SmsProperties sms(boolean mock, boolean devEcho) {
        return new SmsProperties(null, null, null, null, null, null, null, null, null,
                java.util.Map.of(), null, null, true, devEcho, 300, 6, mock, "123456");
    }

    @Test
    void prod_refusesStaffMfaOffOrWithoutAValidKey() {
        var off = new MfaProperties(false, MFA.encryptionKey(), MFA.requiredRoles());
        assertThatThrownBy(() -> guard("prod", STRONG_SECRET, false, false, "signzy", LENDER, off).check())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("navix.mfa.enforce");
        for (String key : new String[] {null, "", "c2hvcnQ="}) {
            var bad = new MfaProperties(true, key, MFA.requiredRoles());
            assertThatThrownBy(() -> guard("prod", STRONG_SECRET, false, false, "signzy", LENDER, bad).check())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("encryption-key");
        }
        var devOff = new MfaProperties(false, null, MFA.requiredRoles());
        assertThatCode(() -> guard("dev", STRONG_SECRET, false, false, "signzy", LENDER, devOff).check())
                .doesNotThrowAnyException();
    }

    @Test
    void dev_bootsWithEveryDemoSettingOn() {
        assertThatCode(() -> guard("dev", DEFAULT_SECRET, true, true).check())
                .doesNotThrowAnyException();
    }

    @Test
    void prod_rejectsTheBuiltInSecret() {
        assertThatThrownBy(() -> guard("prod", DEFAULT_SECRET, false, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_SECRET");
    }

    /** SHA-256 widens any string to a 256-bit key, so length has to be checked separately. */
    @Test
    void prod_rejectsAShortSecret() {
        assertThatThrownBy(() -> guard("prod", "changeme", false, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too-short");
    }

    @Test
    void prod_rejectsMockSms_whichVerifiesAnyMobile() {
        assertThatThrownBy(() -> guard("prod", STRONG_SECRET, true, false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_SMS_MOCK");
    }

    @Test
    void prod_rejectsDevEcho_whichReturnsTheOtpInTheResponse() {
        assertThatThrownBy(() -> guard("prod", STRONG_SECRET, false, true).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_SMS_DEV_ECHO");
    }

    @Test
    void prod_rejectsMockEsign_whichSignsNothing() {
        assertThatThrownBy(() -> guard("prod", STRONG_SECRET, false, false, "mock").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_ESIGN_PROVIDER");
    }

    private static ProductionConfigGuard digioGuard(String env, boolean flagOn) {
        ProductionConfigGuard g = new ProductionConfigGuard(sms(false, false), flags(flagOn), LENDER, MFA);
        ReflectionTestUtils.setField(g, "env", env);
        ReflectionTestUtils.setField(g, "secret", STRONG_SECRET);
        ReflectionTestUtils.setField(g, "esignProvider", "signzy");
        ReflectionTestUtils.setField(g, "digioProvider", "mock");
        return g;
    }

    @Test
    void prod_rejectsMockDigio_whenTheMandateFlagIsOn() {
        assertThatThrownBy(digioGuard("prod", true)::checkDigio)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_DIGIO_PROVIDER");
    }

    /** The feature is off: nothing calls the adapter, so a prod deploy must not be blocked for it. */
    @Test
    void prod_bootsWithMockDigio_whenTheMandateFlagIsOff() {
        assertThatCode(digioGuard("prod", false)::checkDigio).doesNotThrowAnyException();
        assertThatCode(digioGuard("prod", false)::check).doesNotThrowAnyException();
    }

    @Test
    void dev_bootsWithMockDigio() {
        assertThatCode(digioGuard("dev", true)::checkDigio).doesNotThrowAnyException();
    }

    @Test
    void dev_bootsWithMockEsign_whichTheDemoSeedNeeds() {
        assertThatCode(() -> guard("dev", DEFAULT_SECRET, true, true, "mock").check())
                .doesNotThrowAnyException();
    }

    @Test
    void prod_bootsWhenProperlyConfigured() {
        assertThatCode(() -> guard("prod", STRONG_SECRET, false, false).check())
                .doesNotThrowAnyException();
    }

    /** PLAN Phase 5: every loan document names the lender, so a placeholder or blank lender fails the boot. */
    @Test
    void prod_rejectsAPlaceholderLender() {
        LenderProperties placeholder = new LenderProperties("[lender legal name]", LENDER.address(),
                LENDER.registration(), LENDER.contact());
        assertThatThrownBy(() -> guard("prod", STRONG_SECRET, false, false, "signzy", placeholder).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("navix.lender.legal-name");
    }

    @Test
    void prod_rejectsABlankLenderValue() {
        LenderProperties blank = new LenderProperties(LENDER.legalName(), LENDER.address(), " ", null);
        assertThatThrownBy(() -> guard("prod", STRONG_SECRET, false, false, "signzy", blank).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("navix.lender.registration")
                .hasMessageContaining("navix.lender.contact");
    }

    @Test
    void dev_bootsWithThePlaceholderLender() {
        LenderProperties placeholder = new LenderProperties("[lender legal name]", "[a]", "[r]", "[c]");
        assertThatCode(() -> guard("dev", STRONG_SECRET, false, false, "signzy", placeholder).check())
                .doesNotThrowAnyException();
    }
}
