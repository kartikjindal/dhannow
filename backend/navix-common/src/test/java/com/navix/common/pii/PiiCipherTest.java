package com.navix.common.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import java.security.SecureRandom;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PLAN Phase 9: field encryption, the blind index and the staff view of Aadhaar, PAN and account numbers. */
class PiiCipherTest {

    /** A throwaway key per test run; never a real one. */
    private static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    private final PiiCipher cipher = new PiiCipher(key());

    @BeforeEach
    void install() {
        PiiCipher.install(cipher);
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
        PiiView.drainRevealed();
    }

    @Test
    void piiIsStoredEncryptedAndReadBack() {
        String stored = cipher.encrypt("ABCDE1234F");
        assertThat(stored).startsWith(PiiCipher.PREFIX).doesNotContain("ABCDE1234F").doesNotContain("1234");
        assertThat(cipher.encrypt("ABCDE1234F")).isNotEqualTo(stored); // a fresh IV every time
        assertThat(cipher.decrypt(stored)).isEqualTo("ABCDE1234F");
        assertThat(cipher.encrypt(stored)).isEqualTo(stored); // never encrypted twice
    }

    @Test
    void aPlainValueFromBeforeEncryptionReadsAsItIs() {
        assertThat(cipher.decrypt("ABCDE1234F")).isEqualTo("ABCDE1234F");
        assertThat(new PiiConverter().convertToEntityAttribute("123456789012")).isEqualTo("123456789012");
    }

    @Test
    void aTamperedValueOrAnotherKeyFails() {
        String stored = cipher.encrypt("123456789012");
        String tampered = stored.substring(0, stored.length() - 2) + (stored.endsWith("A") ? "BB" : "AA");
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PiiCipher(key()).decrypt(stored)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PiiCipher(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBlindIndexIsStableNormalisedAndKeyed() {
        assertThat(PiiCipher.panIndex(" abcde1234f ")).isEqualTo(PiiCipher.panIndex("ABCDE1234F")).hasSize(64);
        assertThat(PiiCipher.digitsIndex("1234 5678 9012")).isEqualTo(PiiCipher.digitsIndex("123456789012"));
        assertThat(PiiCipher.panIndex("ABCDE1234F")).isNotEqualTo(PiiCipher.panIndex("ABCDE1234G"));
        assertThat(new PiiCipher(key()).index("ABCDE1234F")).isNotEqualTo(cipher.index("ABCDE1234F"));
        assertThat(PiiCipher.panIndex(null)).isNull();
        assertThat(PiiCipher.panIndex("  ")).isNull();
    }

    @Test
    void staffWithoutPiiViewSeeMaskedValues() {
        PiiView.setViewRoles(Set.of("ADMIN"));
        ActorContext.set(new CurrentActor("5", "Officer", "COLLECTION_EXECUTIVE"));
        assertThat(PiiView.pan(cipher.encrypt("ABCDE1234F"))).isEqualTo("ABXXXXX34F");
        assertThat(PiiView.aadhaar("123456789012")).isEqualTo("XXXXXXXX9012");
        assertThat(PiiView.account("50100012345678")).isEqualTo("XXXXXXXXXX5678");
        assertThat(PiiView.drainRevealed()).isEmpty();
    }

    @Test
    void piiViewSeesTheFullValueAndTheRequestIsRecorded() {
        PiiView.setViewRoles(Set.of("ADMIN"));
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        assertThat(PiiView.pan(cipher.encrypt("ABCDE1234F"))).isEqualTo("ABCDE1234F");
        assertThat(PiiView.account("50100012345678")).isEqualTo("50100012345678");
        assertThat(PiiView.drainRevealed()).containsExactly("account", "pan");
        assertThat(PiiView.drainRevealed()).isEmpty();
    }

    @Test
    void theBorrowerAndBackgroundJobsReadTheirOwnInFullWithNoRecord() {
        ActorContext.set(new CurrentActor("7", "Asha", "BORROWER"));
        assertThat(PiiView.aadhaar("123456789012")).isEqualTo("123456789012");
        ActorContext.clear();
        assertThat(PiiView.pan("ABCDE1234F")).isEqualTo("ABCDE1234F");
        assertThat(PiiView.drainRevealed()).isEmpty();
    }
}
