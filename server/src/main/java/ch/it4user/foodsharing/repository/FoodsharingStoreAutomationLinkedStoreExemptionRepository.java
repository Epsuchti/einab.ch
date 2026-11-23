package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomation;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomationLinkedStore;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomationLinkedStoreExemption;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingStoreAutomationLinkedStoreExemptionRepository extends JpaRepository<FoodsharingStoreAutomationLinkedStoreExemption, UUID> {
    List<FoodsharingStoreAutomationLinkedStoreExemption> findAllByLinkOrderByCreatedAtAscIdAsc(FoodsharingStoreAutomationLinkedStore link);
    void deleteAllByLinkAutomation(FoodsharingStoreAutomation automation);
}
