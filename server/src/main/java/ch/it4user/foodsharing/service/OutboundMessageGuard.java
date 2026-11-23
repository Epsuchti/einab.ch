package ch.it4user.foodsharing.service;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Central check for everything that reaches a real person: emails, Foodsharing messages and store-chat posts,
 * Foodsharing actions that notify the affected user (confirm/decline pickups, approve/decline store requests)
 * and Telegram messages.
 * <p>
 * When {@code app.outbound.restricted} is true (every profile except prod and local), only the Foodsharing users on
 * {@code allowed-foodsharing-ids} are reached, by Foodsharing message or by email. Store team chats reach every store
 * member and are always blocked. Telegram chats are not tied to a Foodsharing user (they are often groups), so they
 * need their own {@code allowed-telegram-chat-ids}; empty blocks all of them. Everything blocked is skipped and logged
 * with the marker {@code OUTBOUND BLOCKED}.
 */
@Component
public class OutboundMessageGuard {

    private static final Logger log = LoggerFactory.getLogger(OutboundMessageGuard.class);
    private static final int PREVIEW_LENGTH = 120;

    private final boolean restricted;
    private final Set<String> allowedFoodsharingIds;
    private final Set<String> allowedTelegramChatIds;

    public OutboundMessageGuard(AppProperties appProperties) {
        AppProperties.Outbound outbound = appProperties.getOutbound();
        this.restricted = outbound.isRestricted();
        this.allowedFoodsharingIds = normalizeAll(outbound.getAllowedFoodsharingIds());
        this.allowedTelegramChatIds = normalizeAll(outbound.getAllowedTelegramChatIds());
        if (restricted) {
            log.warn("Outbound messages are RESTRICTED to allowlisted recipients: foodsharingIds={} telegramChatIds={}",
                    allowedFoodsharingIds, allowedTelegramChatIds);
        }
    }

    public boolean isRestricted() {
        return restricted;
    }

    /** Foodsharing direct message or email to this user, or a Foodsharing action that notifies them. */
    public boolean allowFoodsharingUser(String foodsharingId, String what) {
        return check("foodsharing", foodsharingId, contains(allowedFoodsharingIds, normalize(foodsharingId)), what);
    }

    /** Message in the team chat of a Foodsharing store, which reaches every store member: blocked whenever restricted. */
    public boolean allowStoreChat(long storeId, String what) {
        return check("store-chat", String.valueOf(storeId), false, what);
    }

    /** Telegram message to (or deletion in) a chat. */
    public boolean allowTelegramChat(String chatId, String what) {
        return check("telegram", chatId, contains(allowedTelegramChatIds, normalize(chatId)), what);
    }

    private boolean check(String channel, String recipient, boolean allowlisted, String what) {
        if (!restricted || allowlisted) {
            return true;
        }
        log.warn("OUTBOUND BLOCKED channel={} recipient={} content=\"{}\"", channel, recipient, preview(what));
        return false;
    }

    /** Immutable sets throw on {@code contains(null)}; a missing recipient is never allowlisted. */
    private static boolean contains(Set<String> allowlist, String value) {
        return value != null && allowlist.contains(value);
    }

    /** Only the first line (usually the subject), so login links and other secrets in the body never reach the log. */
    private static String preview(String text) {
        if (text == null) return "";
        String singleLine = text.strip().lines().findFirst().orElse("").replaceAll("\\s+", " ").trim();
        return singleLine.length() <= PREVIEW_LENGTH ? singleLine : singleLine.substring(0, PREVIEW_LENGTH) + "…";
    }

    private static Set<String> normalizeAll(Collection<String> values) {
        if (values == null) return Set.of();
        return values.stream().map(OutboundMessageGuard::normalize).filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
