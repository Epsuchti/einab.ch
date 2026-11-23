package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreInfoSettings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FoodsharingStoreInfoSettingsRepository extends JpaRepository<FoodsharingStoreInfoSettings, UUID> {
    Optional<FoodsharingStoreInfoSettings> findByAdminConnection(FoodsharingAdminConnection adminConnection);
    List<FoodsharingStoreInfoSettings> findAllByEnabledTrue();
    void deleteAllByAdminConnection(FoodsharingAdminConnection adminConnection);

    /** Marks the daily check as done without a version check: an admin may edit the settings while the check runs. */
    @Modifying
    @Query("update FoodsharingStoreInfoSettings s set s.lastCheckedAt = :checkedAt where s.id = :id")
    int markChecked(@Param("id") UUID id, @Param("checkedAt") Instant checkedAt);
}
