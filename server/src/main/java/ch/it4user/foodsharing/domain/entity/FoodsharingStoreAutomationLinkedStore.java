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
 * Slot approval rule: a pickup in the automation's store requires a pickup in at least one of the linked stores (see
 * {@link FoodsharingStoreAutomationLinkedStoreMember}). With {@code bidirectional} each linked store's pickups in turn
 * require a pickup in the automation's store.
 */
@Entity
@Table(name = "foodsharing_store_automation_linked_stores")
@Getter
@Setter
@NoArgsConstructor
public class FoodsharingStoreAutomationLinkedStore extends BaseEntity {
    public static final String WINDOW_SAME_DAY = "SAME_DAY";
    /** The linked pickup may be up to {@code windowDaysBefore} calendar days before or {@code windowDaysAfter} days after the pickup. */
    public static final String WINDOW_LAST_DAYS = "LAST_DAYS";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "automation_id", nullable = false)
    private FoodsharingStoreAutomation automation;

    @Column(nullable = false)
    private boolean bidirectional = false;

    @Column(name = "window_mode", nullable = false, length = 32)
    private String windowMode = WINDOW_SAME_DAY;

    @Column(name = "window_days_before", nullable = false)
    private int windowDaysBefore = 0;

    @Column(name = "window_days_after", nullable = false)
    private int windowDaysAfter = 0;

    @Column(name = "grace_minutes", nullable = false)
    private int graceMinutes = 20;

    @Column(length = 32600)
    private String message;
}
