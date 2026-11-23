package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.FoodsharingBezirkMessageConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingMessageAccountTelegram;
import ch.it4user.foodsharing.repository.FoodsharingBezirkMessageConnectionRepository;
import ch.it4user.foodsharing.repository.FoodsharingMessageAccountTelegramRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Forwards Foodsharing messages that a sending account (Bezirk message account or fallback account) sends to
 * itself to a Telegram chat. Foodsharing shows no notification for such messages, so admins would miss them.
 */
@Service
public class FoodsharingMessageTelegramService {
    private static final Logger log = LoggerFactory.getLogger(FoodsharingMessageTelegramService.class);
    private static final int TELEGRAM_MAX_MESSAGE_LENGTH = 4096;

    private final FoodsharingMessageAccountTelegramRepository telegramRepository;
    private final FoodsharingBezirkMessageConnectionRepository connectionRepository;
    private final CryptoService cryptoService;
    private final AppProperties appProperties;
    private final RestClient telegramClient = RestClient.create("https://api.telegram.org");
    private final OutboundMessageGuard outboundGuard;

    public FoodsharingMessageTelegramService(FoodsharingMessageAccountTelegramRepository telegramRepository,
                                             FoodsharingBezirkMessageConnectionRepository connectionRepository,
                                             CryptoService cryptoService,
                                             AppProperties appProperties,
                                             OutboundMessageGuard outboundGuard) {
        this.outboundGuard = outboundGuard;
        this.telegramRepository = telegramRepository;
        this.connectionRepository = connectionRepository;
        this.cryptoService = cryptoService;
        this.appProperties = appProperties;
    }

    /** True when the account has an enabled Telegram forwarding with bot token and chat. */
    @Transactional(readOnly = true)
    public boolean isForwardingConfigured(String accountKey) {
        return telegramRepository.findByAccountKey(accountKey).filter(this::isReady).isPresent();
    }

    /**
     * Sends the text to the account's Telegram chat. Failures are logged and never thrown, so the Foodsharing
     * message itself is still sent.
     */
    @Transactional(readOnly = true)
    public boolean forward(String accountKey, String text) {
        Optional<FoodsharingMessageAccountTelegram> settings = telegramRepository.findByAccountKey(accountKey).filter(this::isReady);
        if (settings.isEmpty()) {
            return false;
        }
        try {
            send(settings.get(), text);
            log.info("Foodsharing self-message of account {} forwarded to Telegram chat {}", accountKey, settings.get().getTelegramChatId());
            return true;
        } catch (RuntimeException exception) {
            log.warn("Forwarding Foodsharing self-message of account {} to Telegram failed: {}", accountKey, exception.getMessage());
            return false;
        }
    }

    @Transactional(readOnly = true)
    public Status status(Bezirk bezirk) {
        Account account = account(bezirk);
        return telegramRepository.findByAccountKey(account.key())
                .map(settings -> new Status(account.email(), account.fallback(), hasToken(settings), settings.isEnabled(),
                        settings.getTelegramChatId(), settings.getTelegramChatTitle()))
                .orElseGet(() -> new Status(account.email(), account.fallback(), false, false, null, null));
    }

    /**
     * Updates the forwarding of the account used by the Bezirk. A blank token keeps the stored token.
     */
    @Transactional
    public Status update(Bezirk bezirk, String botToken, String chatId, String chatTitle, boolean enabled) {
        Account account = account(bezirk);
        FoodsharingMessageAccountTelegram settings = telegramRepository.findByAccountKey(account.key())
                .orElseGet(() -> {
                    FoodsharingMessageAccountTelegram created = new FoodsharingMessageAccountTelegram();
                    created.setAccountKey(account.key());
                    return created;
                });
        String normalizedToken = normalize(botToken);
        if (normalizedToken != null) {
            settings.setTelegramBotTokenCiphertext(cryptoService.encrypt(normalizedToken));
        }
        String normalizedChatId = normalize(chatId);
        settings.setTelegramChatId(normalizedChatId);
        settings.setTelegramChatTitle(normalizedChatId == null ? null : truncate(normalize(chatTitle), 255));
        if (enabled && (!hasToken(settings) || normalizedChatId == null)) {
            throw validationError("Telegram bot token and chat are required to enable the forwarding.");
        }
        settings.setEnabled(enabled);
        telegramRepository.save(settings);
        return status(bezirk);
    }

    @Transactional
    public void remove(Bezirk bezirk) {
        telegramRepository.findByAccountKey(account(bezirk).key()).ifPresent(telegramRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<Chat> chats(Bezirk bezirk) {
        FoodsharingMessageAccountTelegram settings = requireSettingsWithToken(bezirk);
        Object response;
        try {
            response = telegramClient.get().uri("/bot{token}/getUpdates", token(settings)).retrieve().body(Object.class);
        } catch (RuntimeException exception) {
            throw validationError("Telegram chats could not be loaded: " + exception.getMessage());
        }
        Map<String, Chat> chats = new LinkedHashMap<>();
        if (settings.getTelegramChatId() != null) {
            chats.put(settings.getTelegramChatId(), new Chat(settings.getTelegramChatId(),
                    settings.getTelegramChatTitle() == null ? settings.getTelegramChatId() : settings.getTelegramChatTitle(), ""));
        }
        if (response instanceof Map<?, ?> root && root.get("result") instanceof List<?> updates) {
            for (Object update : updates) {
                if (!(update instanceof Map<?, ?> updateMap)) continue;
                Object message = firstPresent(updateMap, "message", "edited_message", "channel_post", "my_chat_member");
                if (message instanceof Map<?, ?> messageMap && messageMap.get("chat") instanceof Map<?, ?> chatMap) {
                    String id = String.valueOf(chatMap.get("id"));
                    if (id.isBlank() || id.equals("null")) continue;
                    String title = chatTitle(chatMap);
                    Object type = chatMap.get("type");
                    chats.put(id, new Chat(id, title == null ? id : title, type == null ? "" : String.valueOf(type)));
                }
            }
        }
        return new ArrayList<>(chats.values());
    }

    @Transactional(readOnly = true)
    public void sendTestMessage(Bezirk bezirk) {
        FoodsharingMessageAccountTelegram settings = requireSettingsWithToken(bezirk);
        if (settings.getTelegramChatId() == null) {
            throw validationError("Select and save a Telegram chat first.");
        }
        Account account = account(bezirk);
        try {
            send(settings, "Foodsharing-Testnachricht\n\nNachrichten, die das Foodsharing-Konto "
                    + (account.email() == null ? "" : account.email() + " ")
                    + "an sich selbst sendet, werden ab jetzt hierher weitergeleitet.");
        } catch (RuntimeException exception) {
            throw validationError("Telegram test message failed: " + exception.getMessage());
        }
    }

    /** Removes the forwarding of a Bezirk message connection that is being deleted. */
    @Transactional
    public void deleteForConnection(FoodsharingBezirkMessageConnection connection) {
        telegramRepository.findByAccountKey(FoodsharingClient.accountKey(Optional.of(connection)))
                .ifPresent(telegramRepository::delete);
    }

    private void send(FoodsharingMessageAccountTelegram settings, String text) {
        if (!outboundGuard.allowTelegramChat(settings.getTelegramChatId(), text)) return;
        telegramClient.post().uri("/bot{token}/sendMessage", token(settings))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("chat_id", settings.getTelegramChatId(), "text", truncate(text, TELEGRAM_MAX_MESSAGE_LENGTH)))
                .retrieve()
                .toBodilessEntity();
    }

    private Account account(Bezirk bezirk) {
        Optional<FoodsharingBezirkMessageConnection> connection = connectionRepository.findByBezirk(bezirk)
                .filter(value -> normalize(value.getFoodsharingEmail()) != null
                        && normalize(value.getFoodsharingPasswordCiphertext()) != null);
        String email = connection.map(FoodsharingBezirkMessageConnection::getFoodsharingEmail)
                .orElse(normalize(appProperties.getFoodsharing().getAdminUser()));
        return new Account(FoodsharingClient.accountKey(connection), email, connection.isEmpty());
    }

    private FoodsharingMessageAccountTelegram requireSettingsWithToken(Bezirk bezirk) {
        return telegramRepository.findByAccountKey(account(bezirk).key())
                .filter(this::hasToken)
                .orElseThrow(() -> validationError("Telegram bot token is not configured."));
    }

    private boolean isReady(FoodsharingMessageAccountTelegram settings) {
        return settings.isEnabled() && hasToken(settings) && normalize(settings.getTelegramChatId()) != null;
    }

    private boolean hasToken(FoodsharingMessageAccountTelegram settings) {
        return normalize(settings.getTelegramBotTokenCiphertext()) != null;
    }

    private String token(FoodsharingMessageAccountTelegram settings) {
        return cryptoService.decrypt(settings.getTelegramBotTokenCiphertext());
    }

    private static String chatTitle(Map<?, ?> chat) {
        Object title = chat.get("title");
        if (title != null) return String.valueOf(title);
        String name = ((chat.get("first_name") == null ? "" : chat.get("first_name")) + " "
                + (chat.get("last_name") == null ? "" : chat.get("last_name"))).trim();
        if (chat.get("username") != null) {
            return name.isEmpty() ? "@" + chat.get("username") : name + " (@" + chat.get("username") + ")";
        }
        return name.isEmpty() ? null : name;
    }

    private static Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.get(key) != null) return map.get(key);
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength - 1) + "…";
    }

    private static ApiException validationError(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of(detail));
    }

    private record Account(String key, String email, boolean fallback) {}

    public record Status(String accountEmail,
                         boolean fallbackAccount,
                         boolean telegramBotTokenConfigured,
                         boolean enabled,
                         String telegramChatId,
                         String telegramChatTitle) {}

    public record Chat(String id, String title, String type) {}
}
