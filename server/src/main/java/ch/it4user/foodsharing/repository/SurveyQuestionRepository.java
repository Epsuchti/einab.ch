package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.SurveyQuestion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SurveyQuestionRepository extends JpaRepository<SurveyQuestion, UUID> {
    List<SurveyQuestion> findAllBySurveyIdOrderBySortOrderAsc(UUID surveyId);
}
