package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "survey_answers")
@Getter
@Setter
@NoArgsConstructor
public class SurveyAnswer extends BaseEntity {

    @Column(name = "response_id", nullable = false)
    private UUID responseId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    /** JSON array of the given values (a single entry except for multiple choice). */
    @Column(name = "value_json", nullable = false, length = 32600)
    private String valueJson = "[]";
}
