package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomation;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomationLinkedStore;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomationLinkedStoreMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingStoreAutomationLinkedStoreMemberRepository extends JpaRepository<FoodsharingStoreAutomationLinkedStoreMember, UUID> {
    List<FoodsharingStoreAutomationLinkedStoreMember> findAllByLinkOrderByStoreNameAscStoreIdAsc(FoodsharingStoreAutomationLinkedStore link);
    List<FoodsharingStoreAutomationLinkedStoreMember> findAllByStoreId(long storeId);
    void deleteAllByLinkAutomation(FoodsharingStoreAutomation automation);
}
