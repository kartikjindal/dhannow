package com.navix.common.notification.event;

import java.time.Instant;
import java.util.UUID;

/** The borrower declined an extension ({@code reason} NOT_NOW or DID_NOT_ASK); the officer is told. */
public record ExtensionDeclinedEvent(UUID extensionId, UUID caseId, Long loanId, Long customerId,
                                     Long officerId, String reason, Instant at) {
}
