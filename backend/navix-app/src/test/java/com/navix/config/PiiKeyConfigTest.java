package com.navix.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.config.PiiKeyConfig.PiiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PLAN Phase 9: where the PII data key may come from. Prod takes only a KMS-wrapped key. */
class PiiKeyConfigTest {

    private final PiiKeyConfig config = new PiiKeyConfig();
    private static final List<String> ROLES = List.of("ADMIN");

    @Test
    void prodRefusesAPlainKeyOrNoKey() {
        assertThatThrownBy(() -> config.piiDataKeySource(new PiiProperties(null, null, null, ROLES), "s", "prod"))
                .hasMessageContaining("NAVIX_PII_KMS_KEY_ID");
        assertThatThrownBy(() -> config.piiDataKeySource(
                new PiiProperties("a-local-key", null, null, ROLES), "s", "prod"))
                .hasMessageContaining("local runs only");
        // A key id without the wrapped key is not a KMS setup either.
        assertThatThrownBy(() -> config.piiDataKeySource(new PiiProperties(null, "alias/x", null, ROLES), "s", "PROD"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void locallyAConfiguredKeyOrTheDevFallbackGivesAStable32ByteKey() {
        byte[] configured = config.piiDataKeySource(new PiiProperties("throwaway", null, null, ROLES), "s", "dev").dataKey();
        assertThat(configured).hasSize(32)
                .isEqualTo(config.piiDataKeySource(new PiiProperties("throwaway", null, null, ROLES), "x", "dev").dataKey());
        byte[] fallback = config.piiDataKeySource(new PiiProperties(null, null, null, ROLES), "secret-a", "dev").dataKey();
        assertThat(fallback).hasSize(32).isNotEqualTo(configured)
                .isNotEqualTo(config.piiDataKeySource(new PiiProperties(null, null, null, ROLES), "secret-b", "dev").dataKey());
    }
}
