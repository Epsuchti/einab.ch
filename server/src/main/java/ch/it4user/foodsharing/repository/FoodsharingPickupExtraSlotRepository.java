package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingPickupExtraSlot;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingPickupExtraSlotRepository extends JpaRepository<FoodsharingPickupExtraSlot, UUID> {
    boolean existsByStoreIdAndPickupDate(long storeId, Instant pickupDate);
    List<FoodsharingPickupExtraSlot> findAllByStoreIdIn(Collection<Long> storeIds);
    List<FoodsharingPickupExtraSlot> findTop200ByAccountKeyOrderByCreatedAtDesc(String accountKey);
}
