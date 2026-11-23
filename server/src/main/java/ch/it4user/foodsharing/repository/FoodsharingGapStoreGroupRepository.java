package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingGapStoreGroup;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingGapStoreGroupRepository extends JpaRepository<FoodsharingGapStoreGroup, UUID> {
    List<FoodsharingGapStoreGroup> findAllByAdminConnection(FoodsharingAdminConnection adminConnection);
    void deleteAllByAdminConnection(FoodsharingAdminConnection adminConnection);
}
