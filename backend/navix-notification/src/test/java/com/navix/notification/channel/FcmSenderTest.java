package com.navix.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.DeviceTokenDirectory;
import com.navix.common.notification.NotificationChannel;
import com.navix.common.notification.RecipientType;
import com.navix.notification.catalog.NotificationType;
import com.navix.notification.template.NotificationTemplates;
import com.navix.notification.template.RenderedMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PLAN Phase 8: the PUSH channel, behind the push-fcm flag, to every signed-in device of the borrower. */
class FcmSenderTest {

    private static final ContactInfo BORROWER = new ContactInfo(RecipientType.BORROWER, 70L, "Asha", null, null, "BORROWER");
    private static final RenderedMessage MSG =
            new RenderedMessage(NotificationChannel.PUSH, "Extension ready", "Confirm in the app", "EXTENSION_OFFERED");

    private final PushClient client = mock(PushClient.class);
    private final DeviceTokenDirectory devices = mock(DeviceTokenDirectory.class);
    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final FcmSender sender = new FcmSender(client, devices, flags);

    @Test
    void flagOffSkipsWithoutLookingUpDevices() {
        assertThat(sender.send(MSG, BORROWER)).isEqualTo(DeliveryOutcome.skipped("PUSH_DISABLED"));
        verifyNoInteractions(client, devices);
    }

    @Test
    void flagOnPushesToEveryDeviceAndForgetsADeadToken() {
        when(flags.isEnabled(FcmSender.FLAG, false)).thenReturn(true);
        when(devices.fcmTokens(70L)).thenReturn(List.of("tok-a", "tok-dead"));
        when(client.send("tok-a", "Extension ready", "Confirm in the app", "EXTENSION_OFFERED"))
                .thenReturn(new PushClient.PushResult(true, false, "msg-1", null));
        when(client.send("tok-dead", "Extension ready", "Confirm in the app", "EXTENSION_OFFERED"))
                .thenReturn(new PushClient.PushResult(false, true, null, "FCM_HTTP_404"));

        assertThat(sender.send(MSG, BORROWER)).isEqualTo(DeliveryOutcome.sent("msg-1"));
        verify(devices).forgetToken("tok-dead"); // CC-61
        verify(devices, never()).forgetToken("tok-a");
    }

    @Test
    void noDeviceOrAStaffRecipientIsSkipped() {
        when(flags.isEnabled(FcmSender.FLAG, false)).thenReturn(true);
        when(devices.fcmTokens(70L)).thenReturn(List.of());
        assertThat(sender.send(MSG, BORROWER).error()).isEqualTo("NO_DEVICE");
        ContactInfo staff = new ContactInfo(RecipientType.STAFF, 1L, "Meera", "m@x.example", null, "ADMIN");
        assertThat(sender.send(MSG, staff).error()).isEqualTo("NOT_BORROWER");
        verify(client, never()).send(any(), any(), any(), any());
    }

    @Test
    void everyDeviceFailingIsAFailedDelivery() {
        when(flags.isEnabled(FcmSender.FLAG, false)).thenReturn(true);
        when(devices.fcmTokens(70L)).thenReturn(List.of("tok-a"));
        when(client.send(any(), any(), any(), any())).thenReturn(new PushClient.PushResult(false, false, null, "FCM_NOT_CONFIGURED"));
        assertThat(sender.send(MSG, BORROWER)).isEqualTo(DeliveryOutcome.failed("FCM_NOT_CONFIGURED"));
    }

    /** The extension notices 6b listed now also go to push, with the in-app wording. */
    @Test
    void extensionNoticesGoToPushWithTheInAppWording() {
        assertThat(NotificationType.EXTENSION_OFFERED.channels()).contains(NotificationChannel.PUSH);
        assertThat(NotificationType.EXTENSION_ACTIVATED.channels()).contains(NotificationChannel.PUSH);
        NotificationTemplates templates = new NotificationTemplates();
        assertThat(templates.get(NotificationType.EXTENSION_OFFERED, NotificationChannel.PUSH))
                .isEqualTo(templates.get(NotificationType.EXTENSION_OFFERED, NotificationChannel.IN_APP))
                .isNotNull();
    }
}
