package com.navix.auth;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/** {@link AuthStateStore} in a map, for unit tests. Same contract as {@link JdbcAuthStateStore}. */
class InMemoryAuthStateStore implements AuthStateStore {

    private record Challenge(String code, Instant expiresAt, int attempts) {
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    @Override
    public Counter updateCounter(String key, UnaryOperator<Counter> fn) {
        return counters.compute(key, (k, current) -> fn.apply(current));
    }

    @Override
    public void clearCounter(String key) {
        counters.remove(key);
    }

    @Override
    public void putChallenge(String purpose, String subject, String code, Instant expiresAt) {
        challenges.put(purpose + ":" + subject, new Challenge(code, expiresAt, 0));
    }

    @Override
    public boolean consumeChallenge(String purpose, String subject, String code, int maxAttempts, Instant now) {
        boolean[] matched = {false};
        challenges.computeIfPresent(purpose + ":" + subject, (k, c) -> {
            if (now.isAfter(c.expiresAt()) || c.attempts() >= maxAttempts) {
                return null;
            }
            if (!c.code().equals(code)) {
                return new Challenge(c.code(), c.expiresAt(), c.attempts() + 1);
            }
            matched[0] = true;
            return null;
        });
        return matched[0];
    }

    @Override
    public void removeChallenge(String purpose, String subject) {
        challenges.remove(purpose + ":" + subject);
    }
}
