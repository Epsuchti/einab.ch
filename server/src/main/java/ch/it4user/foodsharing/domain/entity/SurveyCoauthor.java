package ch.it4user.foodsharing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A user who may edit a survey and read its results besides its owner. */
@Entity
@Table(name = "survey_coauthors", uniqueConstraints = @UniqueConstraint(
        name = "uk_survey_coauthors_survey_user", columnNames = {"survey_id", "foodsharing_id"}))
@Getter
@Setter
@NoArgsConstructor
public class SurveyCoauthor extends BaseEntity {

    @Column(name = "survey_id", nullable = false)
    private UUID surveyId;

    @Column(name = "foodsharing_id", nullable = false, length = 100)
    private String foodsharingId;
}
