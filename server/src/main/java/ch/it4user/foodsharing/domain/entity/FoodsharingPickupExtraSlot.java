package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An extra pickup slot that a user added to a Foodsharing pickup through this app. Only one per pickup is allowed. */
@Entity
@Table(name = "foodsharing_pickup_extra_slots", uniqueConstraints = @UniqueConstraint(
        name = "uk_foodsharing_pickup_extra_slots_store_pickup",
        columnNames = {"store_id", "pickup_date"}))
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingPickupExtraSlot extends BaseEntity {
    /** Foodsharing account that sent the change, e.g. "bezirk:<connection id>" or "fallback". */
    @Column(name = "account_key", length = 150)
    private String accountKey;

    @Column(name = "store_id", nullable = false)
    private long storeId;

    @Column(name = "pickup_date", nullable = false)
    private Instant pickupDate;

    @Column(name = "foodsharing_user_id", nullable = false, length = 100)
    private String foodsharingUserId;

    @Column(name = "foodsharing_user_name", length = 255)
    private String foodsharingUserName;

    @Column(name = "previous_total_slots", nullable = false)
    private int previousTotalSlots;

    @Column(name = "new_total_slots", nullable = false)
    private int newTotalSlots;
}
