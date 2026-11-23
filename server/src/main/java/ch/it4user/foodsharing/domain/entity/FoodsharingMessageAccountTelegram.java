package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Telegram forwarding for a Foodsharing sending account (a Bezirk message account or the global fallback account).
 * Messages the account sends to itself are forwarded to the configured Telegram chat, because Foodsharing does not
 * notify about them.
 */
@Entity
@Table(name = "foodsharing_message_account_telegram", uniqueConstraints = @UniqueConstraint(
        name = "uk_foodsharing_message_account_telegram_account",
        columnNames = "account_key"))
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingMessageAccountTelegram extends BaseEntity {

    /** {@code bezirk:<connection id>} or {@code fallback}, see {@code FoodsharingClient#accountKey}. */
    @Column(nullable = false, length = 150)
    private String accountKey;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 32600)
    private String telegramBotTokenCiphertext;

    @Column(length = 255)
    private String telegramChatId;

    @Column(length = 255)
    private String telegramChatTitle;
}
