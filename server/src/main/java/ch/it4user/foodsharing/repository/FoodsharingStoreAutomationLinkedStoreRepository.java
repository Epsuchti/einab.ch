package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomation;
import ch.it4user.foodsharing.domain.entity.FoodsharingStoreAutomationLinkedStore;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingStoreAutomationLinkedStoreRepository extends JpaRepository<FoodsharingStoreAutomationLinkedStore, UUID> {
    List<FoodsharingStoreAutomationLinkedStore> findAllByAutomationOrderByCreatedAtAscIdAsc(FoodsharingStoreAutomation automation);
    void deleteAllByAutomation(FoodsharingStoreAutomation automation);
}
