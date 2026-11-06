package com.navix.notification.channel;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.notification.ContactInfo;
import com.navix.common.notification.DeviceTokenDirectory;
import com.navix.common.notification.NotificationChannel;
import com.navix.common.notification.RecipientType;
import com.navix.notification.template.RenderedMessage;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The PUSH channel (PLAN Phase 8): the in-app title and body to every signed-in device of the borrower, over
 * {@link PushClient}. Off unless the {@code push-fcm} flag is on (the delivery is then SKIPPED, like a channel with
 * no address). A token FCM reports as unregistered is forgotten (CC-61).
 */
@Component
public class FcmSender implements ChannelSender {

    public static final String FLAG = "push-fcm";

    private final PushClient client;
    private final DeviceTokenDirectory devices;
    private final FeatureFlagService flags;

    public FcmSender(PushClient client, DeviceTokenDirectory devices, FeatureFlagService flags) {
        this.client = client;
        this.devices = devices;
        this.flags = flags;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public DeliveryOutcome send(RenderedMessage message, ContactInfo recipient) {
        try {
            if (recipient.type() != RecipientType.BORROWER || recipient.id() == null) {
                return DeliveryOutcome.skipped("NOT_BORROWER");
            }
            if (!flags.isEnabled(FLAG, false)) {
                return DeliveryOutcome.skipped("PUSH_DISABLED");
            }
            List<String> tokens = devices.fcmTokens(recipient.id());
            if (tokens.isEmpty()) {
                return DeliveryOutcome.skipped("NO_DEVICE");
            }
            String ref = null;
            String error = null;
            for (String token : tokens) {
                PushClient.PushResult r = client.send(token, message.subject(), message.body(), message.smsTemplateKey());
                if (r.sent()) {
                    ref = ref == null ? r.providerRef() : ref;
                } else {
                    error = r.error();
                    if (r.unregistered()) {
                        devices.forgetToken(token);
                    }
                }
            }
            return ref != null || error == null ? DeliveryOutcome.sent(ref) : DeliveryOutcome.failed(error);
        } catch (RuntimeException e) {
            return DeliveryOutcome.failed(e.getClass().getSimpleName());
        }
    }
}
