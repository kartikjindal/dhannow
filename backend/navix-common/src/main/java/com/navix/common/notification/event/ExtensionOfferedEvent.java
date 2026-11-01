package com.navix.common.notification.event;

import java.time.Instant;
import java.util.UUID;

/** An officer offered a loan extension; the Collection Heads approve it (PLAN Phase 6b). */
public record ExtensionOfferedEvent(UUID extensionId, UUID caseId, Long loanId, Long customerId, int seq,
                                    long chargePaise, Long proposedBy, Instant at) {
}
