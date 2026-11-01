package com.navix.notification.channel;

/** Sends one push to one device token (PLAN Phase 8). {@link FcmPushClient} talks to FCM; tests stub it. */
public interface PushClient {

    /** {@code unregistered}: FCM no longer knows the token (app uninstalled or token rotated, CC-61). */
    record PushResult(boolean sent, boolean unregistered, String providerRef, String error) {
    }

    /** Never throws: a transport or provider failure comes back as a result. */
    PushResult send(String fcmToken, String title, String body, String type);
}
