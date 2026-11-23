package ch.it4user.foodsharing.domain.entity;

import ch.it4user.foodsharing.domain.enumtype.PushPlatform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A phone with the store app on which the user turned on push notifications. Every message the platform sends to the
 * user (Foodsharing message or email) is also sent to these devices as a push notification.
 */
@Entity
@Table(name = "push_devices")
@Getter
@Setter
@NoArgsConstructor
public class PushDevice extends BaseEntity {

    /** Foodsharing ID of the signed-in user; the same key the Foodsharing messages are addressed to. */
    @Column(name = "foodsharing_id", nullable = false, length = 64)
    private String foodsharingId;

    /** Firebase Cloud Messaging registration token of the app installation. */
    @Column(nullable = false, unique = true, length = 512)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PushPlatform platform;

    /** Last time the app reported this token (on every app start while signed in). */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
