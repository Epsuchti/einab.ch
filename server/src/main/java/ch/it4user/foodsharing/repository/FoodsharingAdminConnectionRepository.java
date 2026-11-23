package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.domain.entity.User;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FoodsharingAdminConnectionRepository extends JpaRepository<FoodsharingAdminConnection, UUID> {
    Optional<FoodsharingAdminConnection> findByAdminUser(User adminUser);

    /**
     * Stores a refreshed Foodsharing session without a version check and without loading the entity, so a re-login
     * during a long automation run can never collide with a copy of the connection held by another transaction.
     */
    @Modifying
    @Query("update FoodsharingAdminConnection c set c.sessionCookieCiphertext = :cookie, c.csrfTokenCiphertext = :csrf, "
            + "c.foodsharingUserId = coalesce(:foodsharingUserId, c.foodsharingUserId), c.authenticatedAt = :authenticatedAt, c.updatedAt = :authenticatedAt "
            + "where c.id = :id")
    int updateSession(@Param("id") UUID id, @Param("cookie") String cookieCiphertext, @Param("csrf") String csrfTokenCiphertext,
                      @Param("foodsharingUserId") String foodsharingUserId, @Param("authenticatedAt") Instant authenticatedAt);
}
