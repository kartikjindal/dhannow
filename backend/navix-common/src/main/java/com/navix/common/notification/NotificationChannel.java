package com.navix.common.notification;

/**
 * Delivery channel for a notification. {@code IN_APP} is always attempted (the persisted row is the
 * inbox); {@code SMS}/{@code EMAIL} are address-gated per recipient at dispatch — a missing address
 * on an intended channel is recorded as a {@code SKIPPED} delivery rather than a failure.
 * {@code WHATSAPP} goes to the same mobile as SMS (SmartChat template messages). {@code PUSH} (PLAN Phase 8) goes to
 * the borrower's signed-in devices over FCM, with the in-app wording, while the {@code push-fcm} flag is on.
 */
public enum NotificationChannel {
    IN_APP,
    SMS,
    EMAIL,
    WHATSAPP,
    PUSH
}
