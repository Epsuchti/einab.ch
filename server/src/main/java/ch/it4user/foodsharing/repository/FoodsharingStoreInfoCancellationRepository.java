package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreInfoCancellation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingStoreInfoCancellationRepository extends JpaRepository<FoodsharingStoreInfoCancellation, UUID> {
    Optional<FoodsharingStoreInfoCancellation> findByAdminConnectionAndStoreIdAndPickupDateAndFoodsharingUserId(
            FoodsharingAdminConnection adminConnection, long storeId, Instant pickupDate, String foodsharingUserId);
    List<FoodsharingStoreInfoCancellation> findAllByAdminConnectionAndStatus(FoodsharingAdminConnection adminConnection, String status);
    List<FoodsharingStoreInfoCancellation> findTop100ByAdminConnectionOrderByPickupDateDesc(FoodsharingAdminConnection adminConnection);
    void deleteAllByAdminConnection(FoodsharingAdminConnection adminConnection);
    void deleteAllByPickupDateBefore(Instant cutoff);
    List<FoodsharingStoreInfoCancellation> findAllByPickupDateBefore(Instant cutoff);
    List<FoodsharingStoreInfoCancellation> findAllByAdminConnection(FoodsharingAdminConnection adminConnection);
}
