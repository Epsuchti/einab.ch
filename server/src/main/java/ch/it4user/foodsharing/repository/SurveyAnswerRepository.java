package ch.it4user.foodsharing.repository;

import ch.it4user.foodsharing.domain.entity.SurveyAnswer;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SurveyAnswerRepository extends JpaRepository<SurveyAnswer, UUID> {
    List<SurveyAnswer> findAllByResponseIdIn(Collection<UUID> responseIds);
    void deleteAllByQuestionId(UUID questionId);
    void deleteAllByResponseIdIn(Collection<UUID> responseIds);
}
