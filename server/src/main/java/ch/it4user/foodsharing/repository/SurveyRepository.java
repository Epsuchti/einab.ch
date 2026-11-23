package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.Survey;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SurveyRepository extends JpaRepository<Survey, UUID> {
    List<Survey> findAllByOwnerFoodsharingIdOrderByCreatedAtDesc(String ownerFoodsharingId);
}
