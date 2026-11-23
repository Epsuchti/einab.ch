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

@Entity
@Table(name = "foodsharing_store_info_cancellations", uniqueConstraints = @UniqueConstraint(
        name = "uk_foodsharing_store_info_cancellations_pickup_user",
        columnNames = {"admin_connection_id", "store_id", "pickup_date", "foodsharing_user_id"}))
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingStoreInfoCancellation extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_connection_id", nullable = false)
    private FoodsharingAdminConnection adminConnection;

    @Column(name = "store_id", nullable = false)
    private long storeId;

    @Column(nullable = false, length = 255)
    private String storeName = "";

    @Column(nullable = false)
    private Instant pickupDate;

    @Column(nullable = false, length = 100)
    private String foodsharingUserId;

    @Column(length = 255)
    private String foodsharingUserName;

    @Column(nullable = false)
    private Instant cancelledAt;

    /** Last seen slot count of the pickup before it took place. */
    @Column
    private Integer totalSlots;

    @Column
    private Integer occupiedSlots;

    @Column(nullable = false)
    private boolean cancellerRejoined = false;

    @Column
    private Instant snapshotAt;

    @Column
    private Instant immediateNotifiedAt;

    /** OPEN, NOT_REPLACED, REPLACED, REJOINED, PICKUP_REMOVED, UNKNOWN */
    @Column(nullable = false, length = 50)
    private String status = "OPEN";

    @Column
    private Instant finalCheckedAt;

    @Column
    private Instant finalNotifiedAt;

    @Column(nullable = false)
    private boolean dryRun = false;

    @Column(length = 32600)
    private String message;

    @Column(length = 32600)
    private String error;
}
