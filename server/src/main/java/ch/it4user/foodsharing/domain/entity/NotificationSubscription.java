package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import ch.it4user.foodsharing.domain.enumtype.NotificationChannel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class NotificationSubscription extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bezirk_id", nullable = false)
    private Bezirk bezirk;

    /** The signed-in user this subscription belongs to. Null only for legacy anonymous subscriptions (inactive). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Legacy: address of anonymous newsletter subscriptions, no longer used for new subscriptions. */
    @Column(unique = true, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel = NotificationChannel.FOODSHARING;

    /** Address used when {@link #channel} is EMAIL. */
    @Column(name = "delivery_email", length = 255)
    private String deliveryEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LanguageCode language = LanguageCode.DE;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, unique = true, length = 128)
    private String unsubscribeToken;
}
