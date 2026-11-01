package com.navix.notification.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.notification.event.ExtensionActivatedEvent;
import com.navix.common.outbox.Outbox;
import com.navix.common.outbox.OutboxHandler;
import java.util.Set;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The notifications that go with the outbox's documents (PLAN Phase 9, EXT-22): disbursal (ACTIVATE), closure
 * (REPAID) and extension activation. Queued in the state change's transaction, first tried right after the commit
 * on the notification executor, retried with backoff when the dispatch throws. Channel-level failures stay in the
 * delivery log as before (CC-62); a retry re-sends only when the whole dispatch failed.
 */
@Component
public class OutboxNotifications implements OutboxHandler {

    static final String TRANSITION = "NOTIFY_TRANSITION";
    static final String EXTENSION = "NOTIFY_EXTENSION_ACTIVATED";

    private final Outbox outbox;
    private final NotificationEventListener listener;
    private final ObjectMapper json;
    private final Executor executor;

    public OutboxNotifications(Outbox outbox, NotificationEventListener listener, ObjectMapper json,
                               @Qualifier("notificationExecutor") Executor executor) {
        this.outbox = outbox;
        this.listener = listener;
        this.json = json;
        this.executor = executor;
    }

    static boolean viaOutbox(ApplicationTransitionedEvent e) {
        return "ACTIVATE".equals(e.action()) || "REPAID".equals(e.action());
    }

    @EventListener
    public void onTransition(ApplicationTransitionedEvent e) {
        if (viaOutbox(e)) {
            outbox.enqueue(TRANSITION, write(e), executor);
        }
    }

    @EventListener
    public void onExtensionActivated(ExtensionActivatedEvent e) {
        outbox.enqueue(EXTENSION, write(e), executor);
    }

    @Override
    public Set<String> kinds() {
        return Set.of(TRANSITION, EXTENSION);
    }

    @Override
    public void handle(String kind, String payloadJson) {
        try {
            if (TRANSITION.equals(kind)) {
                listener.notifyTransition(json.readValue(payloadJson, ApplicationTransitionedEvent.class));
            } else {
                listener.onExtensionActivated(json.readValue(payloadJson, ExtensionActivatedEvent.class));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable outbox payload", e);
        }
    }

    private String write(Object event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
