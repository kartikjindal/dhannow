package com.navix.config;

import com.navix.common.pii.PiiCipher;
import com.navix.common.pii.PiiView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Loads the PII data key once at boot and installs the {@link PiiCipher} (PLAN Phase 9). Envelope encryption: the
 * data key is stored only wrapped ({@code NAVIX_PII_WRAPPED_DATA_KEY}, the {@code CiphertextBlob} of
 * {@code aws kms generate-data-key --key-spec AES_256}) and unwrapped by AWS KMS under {@code NAVIX_PII_KMS_KEY_ID}.
 * Locally {@code NAVIX_PII_DATA_KEY} (any string, SHA-256'd into the key) may be used instead; with neither, a
 * non-prod run derives a key from {@code AUTH_SECRET} so a dev database stays readable. Prod refuses anything but KMS
 * ({@link ProductionConfigGuard}). No key is ever committed.
 *
 * <p>Runs before JPA ({@link #piiBeforeJpa}), so no entity is read or written without it.
 */
@Configuration
public class PiiKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(PiiKeyConfig.class);

    /** {@code navix.pii.*}. */
    @ConfigurationProperties(prefix = "navix.pii")
    public record PiiProperties(String dataKey, String kmsKeyId, String wrappedDataKey,
                                @DefaultValue({"ADMIN", "CREDIT_HEAD", "CREDIT_EXECUTIVE", "DISBURSEMENT_HEAD"})
                                List<String> viewRoles) {

        public boolean kms() {
            return kmsKeyId != null && !kmsKeyId.isBlank() && wrappedDataKey != null && !wrappedDataKey.isBlank();
        }
    }

    /** Where the 32-byte data key comes from: the KMS-style port. */
    public interface DataKeySource {
        byte[] dataKey();
    }

    @Bean
    DataKeySource piiDataKeySource(PiiProperties props,
            @Value("${navix.auth.secret:navix-local-dev-secret-change-me}") String authSecret,
            @Value("${NAVIX_ENV:dev}") String env) {
        if (props.kms()) {
            return () -> unwrapWithKms(props.kmsKeyId().trim(), props.wrappedDataKey().trim());
        }
        if ("prod".equalsIgnoreCase(env)) {
            throw new IllegalStateException("NAVIX_ENV=prod without a KMS-wrapped PII key — set NAVIX_PII_KMS_KEY_ID "
                    + "and NAVIX_PII_WRAPPED_DATA_KEY (a plain NAVIX_PII_DATA_KEY is for local runs only).");
        }
        if (props.dataKey() != null && !props.dataKey().isBlank()) {
            return () -> sha256(props.dataKey().trim());
        }
        log.warn("No PII key configured (navix.pii.*): using a development key derived from AUTH_SECRET");
        return () -> sha256("dhannow-dev-pii|" + authSecret);
    }

    @Bean
    PiiCipher piiCipher(DataKeySource source, PiiProperties props) {
        PiiCipher cipher = new PiiCipher(source.dataKey());
        PiiCipher.install(cipher);
        PiiView.setViewRoles(Set.copyOf(props.viewRoles()));
        return cipher;
    }

    /** JPA waits for the cipher. */
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor piiBeforeJpa() {
        return new EntityManagerFactoryDependsOnPostProcessor("piiCipher");
    }

    /** KMS {@code Decrypt} of the wrapped data key; region and credentials from the default AWS chain. */
    static byte[] unwrapWithKms(String keyId, String wrappedBase64) {
        try (KmsClient kms = KmsClient.create()) {
            byte[] key = kms.decrypt(r -> r.keyId(keyId)
                            .ciphertextBlob(SdkBytes.fromByteArray(Base64.getDecoder().decode(wrappedBase64))))
                    .plaintext().asByteArray();
            if (key.length != 32) {
                throw new IllegalStateException("The KMS data key must be AES_256 (32 bytes), got " + key.length);
            }
            return key;
        }
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
