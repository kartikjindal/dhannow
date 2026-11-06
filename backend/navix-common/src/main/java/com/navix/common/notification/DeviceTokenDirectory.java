package com.navix.common.notification;

import java.util.List;

/** The FCM tokens of a borrower's signed-in devices (PLAN Phase 8), for the push channel. */
public interface DeviceTokenDirectory {

    List<String> fcmTokens(long customerId);

    /** FCM said the token is no longer registered (app uninstalled, token rotated): stop using it (CC-61). */
    void forgetToken(String fcmToken);
}
