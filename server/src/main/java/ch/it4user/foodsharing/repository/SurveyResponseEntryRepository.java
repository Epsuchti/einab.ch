package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.SurveyResponseEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SurveyResponseEntryRepository extends JpaRepository<SurveyResponseEntry, UUID> {
    boolean existsBySurveyIdAndFoodsharingId(UUID surveyId, String foodsharingId);
    long countBySurveyId(UUID surveyId);
    List<SurveyResponseEntry> findAllBySurveyIdOrderByCreatedAtAsc(UUID surveyId);
}
