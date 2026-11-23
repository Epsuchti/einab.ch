package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Bieb store info: Telegram alerts when a foodsaver signs out of a pickup and nobody takes the slot. */
@Entity
@Table(name = "foodsharing_store_info_settings")
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingStoreInfoSettings extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_connection_id", nullable = false, unique = true)
    private FoodsharingAdminConnection adminConnection;

    @Column(nullable = false)
    private boolean enabled = false;



    @Column(name = "cutoff_enabled", nullable = false)
    private boolean cutoffEnabled = true;

    @Column(name = "maximum_hours_before_pickup", nullable = false)
    private int maximumHoursBeforePickup = 72;

    @Column(name = "telegram_chat_ids_json", nullable = false, length = 32600)
    private String telegramChatIdsJson = "[]";

    @Column(name = "stores_json", nullable = false, length = 32600)
    private String storesJson = "[]";

    /** Only pickups at or after this moment are reported, so enabling the feature does not replay old cancellations. */
    @Column(name = "active_since")
    private Instant activeSince;

    /** When the daily check last ran for these settings. */
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;
}
