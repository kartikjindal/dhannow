package com.navix.auth;

/**
 * The Play Integrity check on app sign-in (PLAN Phase 8): does this token come from our own app, installed from
 * Play, on a genuine Android device? {@link PlayIntegrityClient} asks Google; tests stub it.
 */
public interface IntegrityVerifier {

    /** Never throws: an unreadable token, a provider failure or a missing configuration is a failed check. */
    boolean passes(String integrityToken);
}
