package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.FoodsharingGapStoreGroup;
import ch.it4user.foodsharing.domain.entity.FoodsharingGapStoreGroupMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingGapStoreGroupMemberRepository extends JpaRepository<FoodsharingGapStoreGroupMember, UUID> {
    Optional<FoodsharingGapStoreGroupMember> findByStoreId(long storeId);
    List<FoodsharingGapStoreGroupMember> findAllByGroupOrderByStoreNameAscStoreIdAsc(FoodsharingGapStoreGroup group);
    void deleteAllByGroupAdminConnection(FoodsharingAdminConnection adminConnection);
}
