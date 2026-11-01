package com.navix.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

/** PLAN Phase 8 / CC-34: a provider may only send the borrower back to an allowlisted address. */
class RedirectAllowlistTest {

    private final RedirectAllowlist prod =
            new RedirectAllowlist("https://dhannow.com/app/cb/", "http://localhost:3000/", "prod");
    private final RedirectAllowlist dev =
            new RedirectAllowlist("https://dhannow.com/app/cb/", "http://localhost:3000/", "dev");

    @Test
    void cc34_theAppCallbackPagesAreAllowed() {
        assertThat(prod.allowed("https://dhannow.com/app/cb/digilocker.html?app=7&sid=x")).isTrue();
        assertThat(prod.allowed("https://DHANNOW.com:443/app/cb/esign.html")).isTrue();
        assertThat(prod.require("https://dhannow.com/app/cb/esign.html")).isEqualTo("https://dhannow.com/app/cb/esign.html");
    }

    @Test
    void cc34_otherHostsPathsAndTricksAreRefused() {
        for (String url : new String[] {
                "https://evil.example/app/cb/digilocker.html",
                "https://dhannow.com.evil.example/app/cb/x",
                "https://dhannow.com@evil.example/app/cb/x",
                "https://user@dhannow.com/app/cb/x",
                "http://dhannow.com/app/cb/x",
                "https://dhannow.com:8443/app/cb/x",
                "https://dhannow.com/app/other",
                "https://dhannow.com/app/cb/../../login",
                "https://dhannow.com/app/cb/%2e%2e/login",
                "//evil.example/app/cb/x",
                "javascript:alert(1)",
                "not a url",
                "",
                null}) {
            assertThat(prod.allowed(url)).as(String.valueOf(url)).isFalse();
        }
        assertThatThrownBy(() -> prod.require("https://evil.example/cb"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "REDIRECT_NOT_ALLOWED");
    }

    /** The local web is allowed only outside prod. */
    @Test
    void cc34_localDevOriginOnlyOutsideProd() {
        String local = "http://localhost:3000/kyc/digilocker/callback?app=1";
        assertThat(dev.allowed(local)).isTrue();
        assertThat(prod.allowed(local)).isFalse();
    }
}
