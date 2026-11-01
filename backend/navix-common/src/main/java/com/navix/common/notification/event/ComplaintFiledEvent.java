package com.navix.common.notification.event;

import java.time.Instant;

/**
 * A complaint ticket was opened (PLAN Phase 7). Heard in-transaction by the dispute service (a LOAN_DISPUTE
 * ticket on a loan puts it on hold) and after commit by notifications (the borrower's acknowledgement).
 */
public record ComplaintFiledEvent(Long complaintId, String ticketNo, Long customerId, Long loanId, String category,
                                  String source, Instant resolveDueAt) {
}
