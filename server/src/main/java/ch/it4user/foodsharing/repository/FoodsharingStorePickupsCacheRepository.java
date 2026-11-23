package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingStorePickupsCache;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The pickups cache is disposable: concurrent runs and admin requests may refresh or drop the same row at any time.
 * Reads therefore return a detached snapshot and deletes are bulk statements, so no cache entity sits in a long
 * transaction's persistence context where a version check could fail at flush time.
 */
public interface FoodsharingStorePickupsCacheRepository extends JpaRepository<FoodsharingStorePickupsCache, UUID> {
    Optional<FoodsharingStorePickupsCache> findByAdminConnectionAndStoreId(FoodsharingAdminConnection adminConnection, long storeId);

    @Query("select c.refreshedAt as refreshedAt, c.payloadJson as payloadJson from FoodsharingStorePickupsCache c where c.adminConnection = :adminConnection and c.storeId = :storeId")
    Optional<Snapshot> findSnapshot(@Param("adminConnection") FoodsharingAdminConnection adminConnection, @Param("storeId") long storeId);

    @Modifying
    @Query("delete from FoodsharingStorePickupsCache c where c.adminConnection = :adminConnection")
    int deleteAllByAdminConnection(@Param("adminConnection") FoodsharingAdminConnection adminConnection);

    java.util.List<FoodsharingStorePickupsCache> findAllByAdminConnectionAdminUserBezirk(ch.it4user.foodsharing.domain.entity.Bezirk bezirk);

    @Modifying
    @Query("delete from FoodsharingStorePickupsCache c where c.adminConnection = :adminConnection and c.storeId = :storeId")
    int deleteByAdminConnectionAndStoreId(@Param("adminConnection") FoodsharingAdminConnection adminConnection, @Param("storeId") long storeId);

    interface Snapshot {
        Instant getRefreshedAt();
        String getPayloadJson();
    }
}
