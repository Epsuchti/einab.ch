package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.NotificationSubscription;
import ch.it4user.foodsharing.domain.entity.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationSubscriptionRepository extends JpaRepository<NotificationSubscription, UUID> {

    @EntityGraph(attributePaths = {"bezirk", "user"})
    Optional<NotificationSubscription> findByUser(User user);

    @EntityGraph(attributePaths = {"bezirk", "user"})
    Optional<NotificationSubscription> findByUnsubscribeToken(String unsubscribeToken);

    @Override
    @EntityGraph(attributePaths = {"bezirk", "user"})
    Optional<NotificationSubscription> findById(UUID id);

    @EntityGraph(attributePaths = {"bezirk", "user"})
    List<NotificationSubscription> findAllByBezirkAndActiveTrueAndUserNotNullOrderByCreatedAtAsc(Bezirk bezirk);

    /** Switches off the subscription of a user, e.g. once the user got verified. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update NotificationSubscription s set s.active = false where s.user = :user and s.active = true")
    int deactivateByUser(@Param("user") User user);
}
