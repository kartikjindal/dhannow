package com.navix.common.pii;

import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.util.Masking;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * How a staff response shows an Aadhaar, PAN or account number (PLAN Phase 9): in full for a role holding
 * {@code PII_VIEW} ({@code navix.pii.view-roles}), masked for every other staff role. A borrower always reads their
 * own in full (the wizard re-hydrates from it), and so does code with no request actor (jobs, documents).
 *
 * <p>Every value shown in full to staff is noted for the request; the security filter writes one
 * {@code pii_access_log} row per request that revealed any ({@link #drainRevealed}).
 *
 * <p>The argument may be the stored form: an encrypted value is decrypted first, so native queries can pass the
 * column straight through.
 */
public final class PiiView {

    /** Roles with {@code PII_VIEW}. Set at boot from config. */
    private static volatile Set<String> viewRoles = Set.of("ADMIN", "CREDIT_HEAD", "CREDIT_EXECUTIVE",
            "DISBURSEMENT_HEAD");

    private static final ThreadLocal<Set<String>> REVEALED = new ThreadLocal<>();

    private PiiView() {}

    public static void setViewRoles(Set<String> roles) {
        viewRoles = Set.copyOf(roles);
    }

    public static String pan(String value) {
        return show("pan", value, Masking::maskPan);
    }

    public static String aadhaar(String value) {
        return show("aadhaar", value, Masking::maskAadhaar);
    }

    public static String account(String value) {
        return show("account", value, Masking::maskAccount);
    }

    /** Whether the current actor is staff without {@code PII_VIEW} (and so sees masked values). */
    public static boolean masked() {
        CurrentActor a = ActorContext.get();
        return isStaff(a) && !viewRoles.contains(a.role());
    }

    /** The fields shown in full during this request (and forgets them). Empty when none. */
    public static Set<String> drainRevealed() {
        Set<String> s = REVEALED.get();
        REVEALED.remove();
        return s == null ? Set.of() : s;
    }

    private static String show(String field, String value, UnaryOperator<String> mask) {
        if (value == null) {
            return null;
        }
        String plain = PiiCipher.isEncrypted(value) ? PiiCipher.get().decrypt(value) : value;
        CurrentActor a = ActorContext.get();
        if (!isStaff(a) || plain.isBlank()) {
            return plain;
        }
        if (!viewRoles.contains(a.role())) {
            return mask.apply(plain);
        }
        Set<String> s = REVEALED.get();
        if (s == null) {
            s = new TreeSet<>();
            REVEALED.set(s);
        }
        s.add(field);
        return plain;
    }

    private static boolean isStaff(CurrentActor a) {
        return a != CurrentActor.SYSTEM && a.role() != null && !"BORROWER".equals(a.role())
                && !"SYSTEM".equals(a.role());
    }
}
