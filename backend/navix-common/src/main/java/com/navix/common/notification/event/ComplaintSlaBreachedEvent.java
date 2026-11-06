package com.navix.common.notification.event;

import java.time.Instant;

/**
 * A complaint missed one SLA milestone ({@code milestone} ACK, REVIEW or RESOLVE). Raised once per milestone by
 * the SLA sweep; the assignee (if any) and the ADMINs are told.
 */
public record ComplaintSlaBreachedEvent(Long complaintId, String ticketNo, Long customerId, Long assignedTo,
                                        String milestone, Instant dueAt) {
}
