package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingMessageAccountTelegram;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodsharingMessageAccountTelegramRepository
        extends JpaRepository<FoodsharingMessageAccountTelegram, UUID> {

    Optional<FoodsharingMessageAccountTelegram> findByAccountKey(String accountKey);
}
