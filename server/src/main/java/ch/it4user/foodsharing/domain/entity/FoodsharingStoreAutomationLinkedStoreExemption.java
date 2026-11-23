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

/** A member the linked store rule does not apply to (in both directions of a bidirectional link). */
@Entity
@Table(name = "foodsharing_store_automation_linked_store_exemptions", uniqueConstraints =
        @UniqueConstraint(name = "uk_foodsharing_linked_store_exemptions", columnNames = {"link_id", "foodsharing_id"}))
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingStoreAutomationLinkedStoreExemption extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    private FoodsharingStoreAutomationLinkedStore link;

    @Column(name = "foodsharing_id", nullable = false, length = 100)
    private String foodsharingId;

    @Column(nullable = false, length = 1000)
    private String reason;
}
