package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.SupportRequest;
import ch.it4user.foodsharing.domain.enumtype.SupportRequestStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportRequestRepository extends JpaRepository<SupportRequest, UUID> {

    @EntityGraph(attributePaths = {"user", "bezirk"})
    @Query("select r from SupportRequest r where (:status is null or r.status = :status) order by r.createdAt desc")
    Page<SupportRequest> findPage(@Param("status") SupportRequestStatus status, Pageable pageable);

    long countByStatus(SupportRequestStatus status);
}
