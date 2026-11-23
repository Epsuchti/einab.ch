package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingFuturePickupUsersCache;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FoodsharingFuturePickupUsersCacheRepository extends JpaRepository<FoodsharingFuturePickupUsersCache, UUID> {
    Optional<FoodsharingFuturePickupUsersCache> findByAdminConnectionAndBezirk(
            FoodsharingAdminConnection adminConnection,
            Bezirk bezirk);
    /** Bulk deletes (no version check): the cache row may be refreshed by another request at the same time. */
    @Modifying
    @Query("delete from FoodsharingFuturePickupUsersCache c where c.adminConnection = :adminConnection and c.bezirk = :bezirk")
    int deleteByAdminConnectionAndBezirk(@Param("adminConnection") FoodsharingAdminConnection adminConnection, @Param("bezirk") Bezirk bezirk);

    @Modifying
    @Query("delete from FoodsharingFuturePickupUsersCache c where c.adminConnection = :adminConnection")
    int deleteAllByAdminConnection(@Param("adminConnection") FoodsharingAdminConnection adminConnection);
}
