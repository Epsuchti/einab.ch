package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.FoodsharingBezirkMessageConnection;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FoodsharingMessageService {
    private static final Logger log = LoggerFactory.getLogger(FoodsharingMessageService.class);

    private final FoodsharingClient foodsharingClient;
    private final FoodsharingBezirkMessageConnectionService messageConnectionService;
    private final FoodsharingMessageTelegramService telegramService;
    private final AppProperties appProperties;
    private final PushNotificationService pushNotificationService;

    public FoodsharingMessageService(FoodsharingClient foodsharingClient,
                                     FoodsharingBezirkMessageConnectionService messageConnectionService,
                                     FoodsharingMessageTelegramService telegramService,
                                     AppProperties appProperties,
                                     PushNotificationService pushNotificationService) {
        this.foodsharingClient = foodsharingClient;
        this.messageConnectionService = messageConnectionService;
        this.telegramService = telegramService;
        this.appProperties = appProperties;
        this.pushNotificationService = pushNotificationService;
    }

    /** Sends a Foodsharing message and the same text as a push notification to the user's phones with the app. */
    public void send(String recipientFoodsharingId, String subject, String body) {
        pushNotificationService.sendToUser(recipientFoodsharingId, subject, body);
        sendWithoutPush(recipientFoodsharingId, subject, body);
    }

    /** Foodsharing message only, for content that must not show up on a lock screen (sign-in links). */
    public void sendWithoutPush(String recipientFoodsharingId, String subject, String body) {
        if (DemoAccounts.isDemoId(recipientFoodsharingId)) {
            log.info("Foodsharing message to demo account {} skipped: {}", recipientFoodsharingId, subject);
            return;
        }
        String message = subject + "\n\n" + normalize(body);
        Optional<FoodsharingBezirkMessageConnection> connection = messageConnectionService.findForRecipient(recipientFoodsharingId);
        // Telegram forwarding is configured explicitly, so it also runs when Foodsharing messages are suppressed locally.
        forwardSelfMessageToTelegram(connection, recipientFoodsharingId, message);
        if (!appProperties.getFoodsharing().isSendMessages()) {
            log.info("Foodsharing message suppressed for local development; recipient={}\n{}", recipientFoodsharingId, message);
            return;
        }
        connection.ifPresentOrElse(
                value -> foodsharingClient.sendMessage(value, recipientFoodsharingId, message),
                () -> foodsharingClient.sendMessage(recipientFoodsharingId, message));
    }

    /**
     * Foodsharing does not notify about messages an account sends to itself (e.g. the Bezirk admin account or the
     * fallback account messaging its own owner). Such messages are additionally forwarded to Telegram when configured.
     */
    private void forwardSelfMessageToTelegram(Optional<FoodsharingBezirkMessageConnection> connection,
                                              String recipientFoodsharingId,
                                              String message) {
        if (recipientFoodsharingId == null || recipientFoodsharingId.isBlank()) {
            return;
        }
        String accountKey = FoodsharingClient.accountKey(connection);
        try {
            if (!telegramService.isForwardingConfigured(accountKey)) {
                return;
            }
            String senderFoodsharingId = foodsharingClient.accountFoodsharingUserId(connection);
            if (senderFoodsharingId != null && senderFoodsharingId.equals(recipientFoodsharingId.trim())) {
                telegramService.forward(accountKey, message);
            } else {
                log.info("Foodsharing message to {} not forwarded to Telegram: sending account {} has Foodsharing ID {}.",
                        recipientFoodsharingId, accountKey, senderFoodsharingId);
            }
        } catch (RuntimeException exception) {
            log.warn("Could not check Telegram forwarding for account {}: {}", accountKey, exception.getMessage());
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim().replaceAll("\n{3,}", "\n\n");
    }
}
