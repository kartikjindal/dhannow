package com.navix.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

/** PLAN Phase 8: CORS allows dhannow.com and staff.dhannow.com; the local web only outside prod. */
class WebConfigTest {

    private static final String ALLOWED = "https://dhannow.com,https://staff.dhannow.com";

    private static CorsConfiguration cors(String env) {
        var source = new WebConfig().corsConfigurationSource(ALLOWED, "http://localhost:3000", env);
        var request = new MockHttpServletRequest("OPTIONS", "/api/loans");
        return source.getCorsConfiguration(request);
    }

    @Test
    void prodAllowsOnlyTheTwoSites() {
        CorsConfiguration prod = cors("prod");
        assertThat(prod.checkOrigin("https://dhannow.com")).isNotNull();
        assertThat(prod.checkOrigin("https://staff.dhannow.com")).isNotNull();
        assertThat(prod.checkOrigin("http://localhost:3000")).isNull();
        assertThat(prod.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void devAlsoAllowsTheLocalWeb() {
        assertThat(cors("dev").checkOrigin("http://localhost:3000")).isNotNull();
        assertThat(WebConfig.origins(ALLOWED, " http://localhost:3000 , ", "staging"))
                .containsExactly("https://dhannow.com", "https://staff.dhannow.com", "http://localhost:3000");
    }
}
