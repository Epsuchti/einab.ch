package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.SurveyCoauthor;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SurveyCoauthorRepository extends JpaRepository<SurveyCoauthor, UUID> {
    List<SurveyCoauthor> findAllBySurveyIdOrderByCreatedAtAsc(UUID surveyId);
    List<SurveyCoauthor> findAllByFoodsharingIdIgnoreCase(String foodsharingId);
    boolean existsBySurveyIdAndFoodsharingIdIgnoreCase(UUID surveyId, String foodsharingId);
    void deleteAllBySurveyId(UUID surveyId);
}
