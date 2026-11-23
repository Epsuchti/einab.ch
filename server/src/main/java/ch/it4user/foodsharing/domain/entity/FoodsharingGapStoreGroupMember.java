package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One store of a gap group. Keyed by store id, so a store without an automation can be a member; its pickups still count. */
@Entity
@Table(name = "foodsharing_gap_store_group_members", uniqueConstraints =
        @UniqueConstraint(name = "uk_foodsharing_gap_store_group_members_store", columnNames = {"store_id"}))
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingGapStoreGroupMember extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private FoodsharingGapStoreGroup group;

    @Column(name = "store_id", nullable = false)
    private long storeId;

    @Column(name = "store_name", nullable = false, length = 255)
    private String storeName = "";
}
