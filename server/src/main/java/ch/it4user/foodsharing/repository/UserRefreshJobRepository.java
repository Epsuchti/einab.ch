package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.entity.UserRefreshJob;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRefreshJobRepository extends JpaRepository<UserRefreshJob, UUID> {

    @EntityGraph(attributePaths = {"user"})
    Optional<UserRefreshJob> findFirstByStatusOrderByCreatedAtAsc(String status);

    Optional<UserRefreshJob> findFirstByBezirkOrderByCreatedAtDesc(Bezirk bezirk);

    List<UserRefreshJob> findAllByUserInAndStatus(java.util.Collection<User> users, String status);

    long countByBatchIdAndStatus(UUID batchId, String status);

    long countByBatchId(UUID batchId);

    /**
     * Updates a job's outcome by id, without a version check: {@code enqueue} may move the same job into a new batch
     * while it is being processed, and that must not make the result get lost.
     */
    @Transactional
    @Modifying
    @Query("update UserRefreshJob j set j.status = :status, j.errorCode = :errorCode, j.attempts = j.attempts + 1, j.updatedAt = :now where j.id = :id")
    int finish(@Param("id") UUID id, @Param("status") String status, @Param("errorCode") String errorCode, @Param("now") java.time.Instant now);

    @Transactional
    @Modifying
    @Query("update UserRefreshJob j set j.attempts = j.attempts + 1, j.updatedAt = :now where j.id = :id")
    int countAttempt(@Param("id") UUID id, @Param("now") java.time.Instant now);
}
