package com.navix.common.notification.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The Accountant verified the extension payment and the due date moved (PLAN Phase 6b). Published after the
 * activation's own writes; the addendum PDF and the borrower notification run after commit.
 */
public record ExtensionActivatedEvent(UUID extensionId, Long loanId, Long customerId, int extensionCount,
                                      int cap, long paidPaise, LocalDate oldDueDate, LocalDate newDueDate,
                                      Instant at) {
}
