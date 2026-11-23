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

/** One store of a linked store rule; a pickup in any one store of the rule satisfies it. */
@Entity
@Table(name = "foodsharing_store_automation_linked_store_members", uniqueConstraints =
        @UniqueConstraint(name = "uk_foodsharing_linked_store_members", columnNames = {"link_id", "store_id"}))
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingStoreAutomationLinkedStoreMember extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    private FoodsharingStoreAutomationLinkedStore link;

    @Column(name = "store_id", nullable = false)
    private long storeId;

    @Column(name = "store_name", nullable = false, length = 255)
    private String storeName = "";
}
