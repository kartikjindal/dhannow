package com.navix.common.notification.event;

/** A complaint ticket was resolved; the borrower is told the outcome. */
public record ComplaintResolvedEvent(Long complaintId, String ticketNo, Long customerId, String resolutionNote) {
}
