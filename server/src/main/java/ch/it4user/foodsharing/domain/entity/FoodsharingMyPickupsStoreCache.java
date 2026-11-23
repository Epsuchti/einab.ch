package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Pickups of one store as seen by a Bezirk admin account (or the fallback account), used by the pickups page.
 * Separate from the automation cache: the pickups page never uses the automation connections.
 */
@Entity
@Table(name = "foodsharing_my_pickups_store_cache", uniqueConstraints = @UniqueConstraint(
        name = "uk_foodsharing_my_pickups_store_cache_account_store",
        columnNames = {"account_key", "store_id"}))
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingMyPickupsStoreCache extends BaseEntity {
    @Column(name = "account_key", nullable = false, length = 150)
    private String accountKey;

    @Column(name = "store_id", nullable = false)
    private long storeId;

    @Column(name = "store_name", nullable = false, length = 255)
    private String storeName = "";

    @Column(name = "refreshed_at", nullable = false)
    private Instant refreshedAt;

    @Column(name = "payload_json", nullable = false, length = 32600)
    private String payloadJson;
}
