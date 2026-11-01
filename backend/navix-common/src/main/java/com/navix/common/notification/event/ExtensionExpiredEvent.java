package com.navix.common.notification.event;

import java.time.Instant;
import java.util.UUID;

/** An extension offer lapsed at its pay-by day; the officer on the case is told (PLAN Phase 6b). */
public record ExtensionExpiredEvent(UUID extensionId, UUID caseId, Long loanId, Long customerId,
                                    Long officerId, Instant at) {
}
