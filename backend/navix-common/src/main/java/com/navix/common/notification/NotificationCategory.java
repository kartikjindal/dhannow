package com.navix.common.notification;

/**
 * Coarse grouping of {@code NotificationType}s, used for labelling/filtering in the UI.
 * {@code SECURITY} is reserved for future login-alert / password-change notifications.
 */
public enum NotificationCategory {
    KYC,
    CREDIT,
    DISBURSEMENT,
    REPAYMENT,
    COLLECTIONS,
    /** Loan extension (PLAN Phase 6b): the bell deep-links to the extension screen by loan id. */
    EXTENSION,
    STAFF_IAM,
    SECURITY,
    SYSTEM
}
