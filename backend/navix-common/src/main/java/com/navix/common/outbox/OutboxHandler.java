package com.navix.common.outbox;

import java.util.Set;

/**
 * Does after-commit side effects for {@link Outbox}. Must be idempotent: a row can run twice (a retry after a crash
 * between the work and the mark).
 */
public interface OutboxHandler {

    /** The {@code outbox.kind} values this handler runs. */
    Set<String> kinds();

    /** Do the work; throw to have it retried with backoff. */
    void handle(String kind, String payloadJson);
}
