package ch.it4user.foodsharing.domain.enumtype;

public enum SurveyQuestionType {
    TEXT,
    MULTIPLE_CHOICE,
    SINGLE_CHOICE,
    BOOLEAN,
    /** Read-only value taken from the "store" parameter of the survey link. */
    STORE
}
