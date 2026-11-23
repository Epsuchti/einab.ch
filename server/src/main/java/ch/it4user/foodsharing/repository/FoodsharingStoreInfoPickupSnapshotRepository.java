package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreInfoPickupSnapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingStoreInfoPickupSnapshotRepository extends JpaRepository<FoodsharingStoreInfoPickupSnapshot, UUID> {
    Optional<FoodsharingStoreInfoPickupSnapshot> findByAdminConnectionAndStoreIdAndPickupDate(
            FoodsharingAdminConnection adminConnection, long storeId, Instant pickupDate);
    void deleteAllByAdminConnection(FoodsharingAdminConnection adminConnection);
    void deleteAllByPickupDateBefore(Instant cutoff);
}
