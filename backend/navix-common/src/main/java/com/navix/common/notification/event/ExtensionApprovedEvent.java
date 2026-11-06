package com.navix.common.notification.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The Collection Head approved an extension: the borrower is told it is on offer (PLAN Phase 6b). */
public record ExtensionApprovedEvent(UUID extensionId, UUID caseId, Long loanId, Long customerId,
                                     long chargePaise, LocalDate payBy, LocalDate newDueDate, Instant at) {
}
