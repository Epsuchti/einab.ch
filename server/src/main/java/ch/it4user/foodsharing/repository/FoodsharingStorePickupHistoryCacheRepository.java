package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingStorePickupHistoryCache;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Disposable like the pickups cache: reads return a detached snapshot and deletes are bulk statements. */
public interface FoodsharingStorePickupHistoryCacheRepository extends JpaRepository<FoodsharingStorePickupHistoryCache, UUID> {
    Optional<FoodsharingStorePickupHistoryCache> findByAdminConnectionAndStoreId(FoodsharingAdminConnection adminConnection, long storeId);

    @Query("select c.fetchedUntil as fetchedUntil, c.validUntil as validUntil, c.payloadJson as payloadJson from FoodsharingStorePickupHistoryCache c where c.adminConnection = :adminConnection and c.storeId = :storeId")
    Optional<Snapshot> findSnapshot(@Param("adminConnection") FoodsharingAdminConnection adminConnection, @Param("storeId") long storeId);

    @Modifying
    @Query("delete from FoodsharingStorePickupHistoryCache c where c.adminConnection = :adminConnection")
    int deleteAllByAdminConnection(@Param("adminConnection") FoodsharingAdminConnection adminConnection);

    interface Snapshot {
        Instant getFetchedUntil();
        Instant getValidUntil();
        String getPayloadJson();
    }
}
