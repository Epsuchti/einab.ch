package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Slot count and occupancy of an upcoming pickup, as seen by the latest daily Bieb Store Info check before it.
 * The pickup history has no slot count, so this is what tells whether a slot stayed empty.
 */
@Entity
@Table(name = "foodsharing_store_info_pickup_snapshots", uniqueConstraints = @UniqueConstraint(
        name = "uk_foodsharing_store_info_pickup_snapshots_pickup",
        columnNames = {"admin_connection_id", "store_id", "pickup_date"}))
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingStoreInfoPickupSnapshot extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_connection_id", nullable = false)
    private FoodsharingAdminConnection adminConnection;

    @Column(name = "store_id", nullable = false)
    private long storeId;

    @Column(name = "pickup_date", nullable = false)
    private Instant pickupDate;

    @Column(nullable = false)
    private int totalSlots;

    @Column(nullable = false)
    private int occupiedSlots;

    @Column(nullable = false)
    private Instant checkedAt;
}
