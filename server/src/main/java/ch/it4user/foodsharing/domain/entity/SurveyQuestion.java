package ch.it4user.foodsharing.domain.entity;

import ch.it4user.foodsharing.domain.enumtype.SurveyQuestionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "survey_questions")
@Getter
@Setter
@NoArgsConstructor
public class SurveyQuestion extends BaseEntity {

    @Column(name = "survey_id", nullable = false)
    private UUID surveyId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 20)
    private SurveyQuestionType type;

    @Column(nullable = false, length = 500)
    private String label;

    @Column(nullable = false)
    private boolean required;

    /** JSON array of option texts; only used by choice questions. */
    @Column(name = "options_json", nullable = false, length = 32600)
    private String optionsJson = "[]";
}
