package com.navix.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.function.UnaryOperator;

/**
 * Where OTP codes and attempt counters live (PLAN Phase 8). Production is {@link JdbcAuthStateStore}: Postgres,
 * so every app instance sees the same counters and a restart forgets nothing (CC-36). The interface exists so
 * the unit tests can run the services over an in-memory copy.
 */
public interface AuthStateStore {

    /**
     * One limiter key's fixed window.
     *
     * @param count       attempts seen in the window that started at {@code windowStart}
     * @param windowStart when the current counting window began
     * @param blockUntil  when the current cool-off ends, or null when not blocked
     */
    record Counter(int count, Instant windowStart, Instant blockUntil) {
        public boolean blockedAt(Instant now) {
            return blockUntil != null && now.isBefore(blockUntil);
        }

        public boolean windowOverAt(Instant now, Duration window) {
            return now.isAfter(windowStart.plus(window));
        }
    }

    /** Atomically replace {@code key}'s counter with {@code fn(current)}; {@code current} is null when absent. */
    Counter updateCounter(String key, UnaryOperator<Counter> fn);

    void clearCounter(String key);

    /** Store a fresh code for {@code purpose}+{@code subject}, replacing any earlier one and its attempt count. */
    void putChallenge(String purpose, String subject, String code, Instant expiresAt);

    /**
     * Check {@code code} once, atomically. A match consumes the challenge (single use). A miss counts one
     * attempt. An expired challenge, or one that already had {@code maxAttempts} misses, is dropped.
     *
     * @return true only when the code matched a live challenge
     */
    boolean consumeChallenge(String purpose, String subject, String code, int maxAttempts, Instant now);

    /** Forget a challenge without checking it (mock-mode logins). */
    void removeChallenge(String purpose, String subject);
}
