package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreMembersCache;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Disposable cache, handled like {@link FoodsharingStorePickupsCacheRepository}: snapshot reads, bulk deletes. */
public interface FoodsharingStoreMembersCacheRepository extends JpaRepository<FoodsharingStoreMembersCache, UUID> {
    Optional<FoodsharingStoreMembersCache> findByAdminConnectionAndStoreId(FoodsharingAdminConnection adminConnection, long storeId);

    @Query("select c.refreshedAt as refreshedAt, c.payloadJson as payloadJson from FoodsharingStoreMembersCache c where c.adminConnection = :adminConnection and c.storeId = :storeId")
    Optional<Snapshot> findSnapshot(@Param("adminConnection") FoodsharingAdminConnection adminConnection, @Param("storeId") long storeId);

    @Modifying
    @Query("delete from FoodsharingStoreMembersCache c where c.adminConnection = :adminConnection")
    int deleteAllByAdminConnection(@Param("adminConnection") FoodsharingAdminConnection adminConnection);

    @Modifying
    @Query("delete from FoodsharingStoreMembersCache c where c.adminConnection = :adminConnection and c.storeId = :storeId")
    int deleteByAdminConnectionAndStoreId(@Param("adminConnection") FoodsharingAdminConnection adminConnection, @Param("storeId") long storeId);

    interface Snapshot {
        Instant getRefreshedAt();
        String getPayloadJson();
    }
}
