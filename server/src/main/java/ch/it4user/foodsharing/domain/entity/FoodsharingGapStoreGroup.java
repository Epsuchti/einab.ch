package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stores that share a gap (see {@link FoodsharingGapStoreGroupMember}): {@code minimumGapDays} applies between pickups in
 * different stores of the group. Pickups within one store keep following that store's own gap rule.
 */
@Entity
@Table(name = "foodsharing_gap_store_groups")
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingGapStoreGroup extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_connection_id", nullable = false)
    private FoodsharingAdminConnection adminConnection;

    @Column(name = "minimum_gap_days", nullable = false)
    private int minimumGapDays = 1;
}
