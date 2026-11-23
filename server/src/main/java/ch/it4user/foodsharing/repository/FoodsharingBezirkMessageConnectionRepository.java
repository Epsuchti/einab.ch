package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.FoodsharingBezirkMessageConnection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FoodsharingBezirkMessageConnectionRepository
        extends JpaRepository<FoodsharingBezirkMessageConnection, UUID> {

    Optional<FoodsharingBezirkMessageConnection> findByBezirk(Bezirk bezirk);

    /** Stores a refreshed session without a version check, so a re-login never collides with a copy held by another transaction. */
    @Modifying
    @Query("update FoodsharingBezirkMessageConnection c set c.sessionCookieCiphertext = :cookie, c.csrfTokenCiphertext = :csrf, "
            + "c.foodsharingUserId = coalesce(:foodsharingUserId, c.foodsharingUserId), c.authenticatedAt = :authenticatedAt, c.updatedAt = :authenticatedAt "
            + "where c.id = :id")
    int updateSession(@Param("id") UUID id, @Param("cookie") String cookieCiphertext, @Param("csrf") String csrfTokenCiphertext,
                      @Param("foodsharingUserId") String foodsharingUserId, @Param("authenticatedAt") Instant authenticatedAt);
}
