package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingMyPickupsStoreCache;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingMyPickupsStoreCacheRepository extends JpaRepository<FoodsharingMyPickupsStoreCache, UUID> {
    List<FoodsharingMyPickupsStoreCache> findAllByAccountKey(String accountKey);
    Optional<FoodsharingMyPickupsStoreCache> findByAccountKeyAndStoreId(String accountKey, long storeId);
}
