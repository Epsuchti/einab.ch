package ch.it4user.foodsharing.web;

import ch.it4user.foodsharing.domain.entity.Survey;
import ch.it4user.foodsharing.domain.entity.SurveyQuestion;
import ch.it4user.foodsharing.openapi.api.SurveyApi;
import ch.it4user.foodsharing.openapi.model.SurveyAnswerValue;
import ch.it4user.foodsharing.openapi.model.SurveyCoauthorCandidatesResponse;
import ch.it4user.foodsharing.openapi.model.SurveyDetail;
import ch.it4user.foodsharing.openapi.model.SurveyForm;
import ch.it4user.foodsharing.openapi.model.SurveyListResponse;
import ch.it4user.foodsharing.openapi.model.SurveyPerson;
import ch.it4user.foodsharing.openapi.model.SurveyQuestionType;
import ch.it4user.foodsharing.openapi.model.SurveyResultRow;
import ch.it4user.foodsharing.openapi.model.SurveyResultsResponse;
import ch.it4user.foodsharing.openapi.model.SurveySaveRequest;
import ch.it4user.foodsharing.openapi.model.SurveySubmitRequest;
import ch.it4user.foodsharing.openapi.model.SurveySummary;
import ch.it4user.foodsharing.service.SurveyService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SurveyController implements SurveyApi {

    private final SurveyService surveyService;

    public SurveyController(SurveyService surveyService) {
        this.surveyService = surveyService;
    }

    @Override
    public ResponseEntity<SurveyListResponse> listMySurveys() {
        SurveyListResponse response = new SurveyListResponse();
        response.setSurveys(surveyService.listMySurveys().stream().map(view -> {
            SurveySummary summary = new SurveySummary();
            summary.setId(view.survey().getId());
            summary.setTitle(view.survey().getTitle());
            summary.setDescription(view.survey().getDescription());
            summary.setResponseCount(view.responseCount());
            summary.setCreatedAt(toOffsetDateTime(view.survey().getCreatedAt()));
            summary.setUpdatedAt(toOffsetDateTime(view.survey().getUpdatedAt()));
            summary.setOwner(view.owner());
            summary.setOwnerName(view.ownerPerson().name());
            summary.setCoauthorCount(view.coauthors().size());
            return summary;
        }).toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<SurveyCoauthorCandidatesResponse> listSurveyCoauthorCandidates() {
        SurveyCoauthorCandidatesResponse response = new SurveyCoauthorCandidatesResponse();
        response.setUsers(surveyService.coauthorCandidates().stream().map(SurveyController::toPerson).toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<SurveyDetail> createSurvey(SurveySaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(
                surveyService.create(request.getTitle(), request.getDescription(), toInputs(request), request.getCoauthorFoodsharingIds())));
    }

    @Override
    public ResponseEntity<SurveyDetail> getMySurvey(UUID surveyId) {
        return ResponseEntity.ok(toDetail(surveyService.getMySurvey(surveyId)));
    }

    @Override
    public ResponseEntity<SurveyDetail> updateSurvey(UUID surveyId, SurveySaveRequest request) {
        return ResponseEntity.ok(toDetail(
                surveyService.update(surveyId, request.getTitle(), request.getDescription(), toInputs(request),
                        request.getCoauthorFoodsharingIds())));
    }

    @Override
    public ResponseEntity<Void> deleteSurvey(UUID surveyId) {
        surveyService.delete(surveyId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<SurveyResultsResponse> getSurveyResults(UUID surveyId) {
        SurveyService.ResultsView results = surveyService.results(surveyId);
        SurveyResultsResponse response = new SurveyResultsResponse();
        response.setSurveyId(results.survey().getId());
        response.setTitle(results.survey().getTitle());
        response.setQuestions(toQuestions(results.questions()));
        response.setResponses(results.rows().stream().map(row -> {
            SurveyResultRow resultRow = new SurveyResultRow();
            resultRow.setFoodsharingId(row.foodsharingId());
            resultRow.setName(row.name());
            resultRow.setSubmittedAt(toOffsetDateTime(row.submittedAt()));
            resultRow.setAnswers(row.answers().entrySet().stream().map(entry -> {
                SurveyAnswerValue value = new SurveyAnswerValue();
                value.setQuestionId(entry.getKey());
                value.setValues(entry.getValue());
                return value;
            }).toList());
            return resultRow;
        }).toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<SurveyForm> getSurveyForm(UUID surveyId) {
        SurveyService.FormView form = surveyService.form(surveyId);
        SurveyForm response = new SurveyForm();
        response.setId(form.survey().getId());
        response.setTitle(form.survey().getTitle());
        response.setDescription(form.survey().getDescription());
        response.setQuestions(toQuestions(form.questions()));
        response.setAlreadySubmitted(form.alreadySubmitted());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> submitSurveyResponse(UUID surveyId, SurveySubmitRequest request) {
        surveyService.submit(surveyId, request.getAnswers().stream()
                .map(answer -> new SurveyService.AnswerInput(answer.getQuestionId(), answer.getValues()))
                .toList());
        return ResponseEntity.noContent().build();
    }

    private List<SurveyService.QuestionInput> toInputs(SurveySaveRequest request) {
        return request.getQuestions().stream()
                .map(question -> new SurveyService.QuestionInput(question.getId(),
                        ch.it4user.foodsharing.domain.enumtype.SurveyQuestionType.valueOf(question.getType().getValue()),
                        question.getLabel(), Boolean.TRUE.equals(question.getRequired()), question.getOptions()))
                .toList();
    }

    private SurveyDetail toDetail(SurveyService.SurveyView view) {
        Survey survey = view.survey();
        SurveyDetail detail = new SurveyDetail();
        detail.setId(survey.getId());
        detail.setTitle(survey.getTitle());
        detail.setDescription(survey.getDescription());
        detail.setQuestions(toQuestions(view.questions()));
        detail.setResponseCount(view.responseCount());
        detail.setCreatedAt(toOffsetDateTime(survey.getCreatedAt()));
        detail.setUpdatedAt(toOffsetDateTime(survey.getUpdatedAt()));
        detail.setOwner(view.owner());
        detail.setOwnerFoodsharingId(view.ownerPerson().foodsharingId());
        detail.setOwnerName(view.ownerPerson().name());
        detail.setCoauthors(view.coauthors().stream().map(SurveyController::toPerson).toList());
        return detail;
    }

    private static SurveyPerson toPerson(SurveyService.Person person) {
        SurveyPerson response = new SurveyPerson();
        response.setFoodsharingId(person.foodsharingId());
        response.setName(person.name());
        return response;
    }

    private List<ch.it4user.foodsharing.openapi.model.SurveyQuestion> toQuestions(List<SurveyQuestion> questions) {
        return questions.stream().map(question -> {
            ch.it4user.foodsharing.openapi.model.SurveyQuestion response = new ch.it4user.foodsharing.openapi.model.SurveyQuestion();
            response.setId(question.getId());
            response.setType(SurveyQuestionType.fromValue(question.getType().name()));
            response.setLabel(question.getLabel());
            response.setRequired(question.isRequired());
            response.setOptions(surveyService.readList(question.getOptionsJson()));
            return response;
        }).toList();
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
