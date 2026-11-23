package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.AccountDeletionToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountDeletionTokenRepository extends JpaRepository<AccountDeletionToken, UUID> {

    Optional<AccountDeletionToken> findByTokenHash(String tokenHash);

    long countByFoodsharingIdIgnoreCaseAndCreatedAtAfter(String foodsharingId, Instant createdAfter);
}
