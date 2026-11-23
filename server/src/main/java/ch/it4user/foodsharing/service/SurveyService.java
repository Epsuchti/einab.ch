package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Survey;
import ch.it4user.foodsharing.domain.entity.SurveyAnswer;
import ch.it4user.foodsharing.domain.entity.SurveyCoauthor;
import ch.it4user.foodsharing.domain.entity.SurveyQuestion;
import ch.it4user.foodsharing.domain.entity.SurveyResponseEntry;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.SurveyQuestionType;
import ch.it4user.foodsharing.repository.SurveyAnswerRepository;
import ch.it4user.foodsharing.repository.SurveyCoauthorRepository;
import ch.it4user.foodsharing.repository.SurveyQuestionRepository;
import ch.it4user.foodsharing.repository.SurveyRepository;
import ch.it4user.foodsharing.repository.SurveyResponseEntryRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Surveys are created by users with the survey permission and answered by any signed-in Foodsharing user (once per survey). The
 * creator and the co-authors they chose (other users with the survey permission) can edit a survey and read its responses; only the
 * creator can delete it or change its co-authors.
 */
@Service
public class SurveyService {

    private static final int MAX_ANSWER_LENGTH = 5000;
    private static final int MAX_STORE_LENGTH = 200;
    private static final int MAX_COAUTHORS = 20;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository questionRepository;
    private final SurveyResponseEntryRepository responseRepository;
    private final SurveyAnswerRepository answerRepository;
    private final SurveyCoauthorRepository coauthorRepository;
    private final UserRepository userRepository;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;

    public SurveyService(SurveyRepository surveyRepository,
                         SurveyQuestionRepository questionRepository,
                         SurveyResponseEntryRepository responseRepository,
                         SurveyAnswerRepository answerRepository,
                         SurveyCoauthorRepository coauthorRepository,
                         UserRepository userRepository,
                         CurrentActorService currentActorService,
                         ObjectMapper objectMapper) {
        this.surveyRepository = surveyRepository;
        this.questionRepository = questionRepository;
        this.responseRepository = responseRepository;
        this.answerRepository = answerRepository;
        this.coauthorRepository = coauthorRepository;
        this.userRepository = userRepository;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
    }

    public record QuestionInput(UUID id, SurveyQuestionType type, String label, boolean required, List<String> options) {
    }

    public record AnswerInput(UUID questionId, List<String> values) {
    }

    public record Person(String foodsharingId, String name) {
    }

    /** {@code owner} tells whether the current user created the survey (and may delete it or change its co-authors). */
    public record SurveyView(Survey survey, List<SurveyQuestion> questions, long responseCount, boolean owner, Person ownerPerson,
                             List<Person> coauthors) {
    }

    public record ResultRow(String foodsharingId, String name, java.time.Instant submittedAt, Map<UUID, List<String>> answers) {
    }

    public record ResultsView(Survey survey, List<SurveyQuestion> questions, List<ResultRow> rows) {
    }

    public record FormView(Survey survey, List<SurveyQuestion> questions, boolean alreadySubmitted) {
    }

    @Transactional(readOnly = true)
    public List<SurveyView> listMySurveys() {
        String me = requireManager();
        List<Survey> surveys = new ArrayList<>(surveyRepository.findAllByOwnerFoodsharingIdOrderByCreatedAtDesc(me));
        Set<UUID> seen = new HashSet<>(surveys.stream().map(Survey::getId).toList());
        List<UUID> shared = coauthorRepository.findAllByFoodsharingIdIgnoreCase(me).stream()
                .map(SurveyCoauthor::getSurveyId).filter(seen::add).toList();
        surveys.addAll(surveyRepository.findAllById(shared));
        surveys.sort(Comparator.comparing(Survey::getCreatedAt).reversed());
        return surveys.stream().map(survey -> view(survey, me, false)).toList();
    }

    /** Users that can be added as co-authors: everyone else with the survey permission. */
    @Transactional(readOnly = true)
    public List<Person> coauthorCandidates() {
        String me = requireManager();
        return userRepository.findAllByCanManageSurveysTrueAndActiveTrueOrderByNameAsc().stream()
                .filter(user -> !user.getFoodsharingId().equalsIgnoreCase(me))
                .map(user -> new Person(user.getFoodsharingId(), user.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SurveyView getMySurvey(UUID surveyId) {
        Survey survey = requireAccessibleSurvey(surveyId);
        return view(survey, currentActorService.requireFoodsharingId(), true);
    }

    @Transactional
    public SurveyView create(String title, String description, List<QuestionInput> questions, List<String> coauthorIds) {
        String owner = requireManager();
        validateDefinition(title, questions);
        List<String> coauthors = validateCoauthors(owner, coauthorIds);
        Survey survey = new Survey();
        survey.setOwnerFoodsharingId(owner);
        survey.setTitle(title.trim());
        survey.setDescription(description == null ? "" : description.trim());
        surveyRepository.save(survey);
        for (int index = 0; index < questions.size(); index++) {
            SurveyQuestion question = new SurveyQuestion();
            question.setSurveyId(survey.getId());
            applyQuestion(question, questions.get(index), index);
            questionRepository.save(question);
        }
        saveCoauthors(survey.getId(), coauthors);
        return view(survey, owner, true);
    }

    @Transactional
    /** {@code coauthorIds} is only applied for the owner; null keeps the current co-authors. */
    public SurveyView update(UUID surveyId, String title, String description, List<QuestionInput> questions, List<String> coauthorIds) {
        Survey survey = requireAccessibleSurvey(surveyId);
        String me = currentActorService.requireFoodsharingId();
        boolean owner = isOwner(survey, me);
        validateDefinition(title, questions);
        List<String> coauthors = owner && coauthorIds != null ? validateCoauthors(me, coauthorIds) : null;
        survey.setTitle(title.trim());
        survey.setDescription(description == null ? "" : description.trim());

        Map<UUID, SurveyQuestion> existing = new HashMap<>();
        questionRepository.findAllBySurveyIdOrderBySortOrderAsc(surveyId)
                .forEach(question -> existing.put(question.getId(), question));
        Set<UUID> kept = new HashSet<>();
        for (QuestionInput input : questions) {
            if (input.id() != null) {
                if (!existing.containsKey(input.id()) || !kept.add(input.id())) {
                    throw invalid("questions.id");
                }
            }
        }
        // Answers of removed questions, or of questions whose type changed, can no longer be interpreted.
        for (SurveyQuestion question : existing.values()) {
            QuestionInput input = questions.stream().filter(candidate -> question.getId().equals(candidate.id())).findFirst().orElse(null);
            if (input == null || input.type() != question.getType()) {
                answerRepository.deleteAllByQuestionId(question.getId());
            }
        }
        answerRepository.flush();
        existing.values().stream().filter(question -> !kept.contains(question.getId()))
                .forEach(questionRepository::delete);
        questionRepository.flush();

        for (int index = 0; index < questions.size(); index++) {
            QuestionInput input = questions.get(index);
            SurveyQuestion question = input.id() == null ? new SurveyQuestion() : existing.get(input.id());
            question.setSurveyId(surveyId);
            applyQuestion(question, input, index);
            questionRepository.save(question);
        }
        surveyRepository.save(survey);
        if (coauthors != null) {
            coauthorRepository.deleteAllBySurveyId(surveyId);
            coauthorRepository.flush();
            saveCoauthors(surveyId, coauthors);
        }
        return view(survey, me, true);
    }

    @Transactional
    public void delete(UUID surveyId) {
        Survey survey = requireOwnSurvey(surveyId);
        coauthorRepository.deleteAllBySurveyId(surveyId);
        coauthorRepository.flush();
        List<SurveyResponseEntry> responses = responseRepository.findAllBySurveyIdOrderByCreatedAtAsc(surveyId);
        if (!responses.isEmpty()) {
            answerRepository.deleteAllByResponseIdIn(responses.stream().map(SurveyResponseEntry::getId).toList());
            answerRepository.flush();
            responseRepository.deleteAll(responses);
            responseRepository.flush();
        }
        questionRepository.deleteAll(questionRepository.findAllBySurveyIdOrderBySortOrderAsc(surveyId));
        questionRepository.flush();
        surveyRepository.delete(survey);
    }

    @Transactional(readOnly = true)
    public ResultsView results(UUID surveyId) {
        Survey survey = requireAccessibleSurvey(surveyId);
        List<SurveyQuestion> questions = questionRepository.findAllBySurveyIdOrderBySortOrderAsc(surveyId);
        List<SurveyResponseEntry> responses = responseRepository.findAllBySurveyIdOrderByCreatedAtAsc(surveyId);
        Map<UUID, Map<UUID, List<String>>> answersByResponse = new HashMap<>();
        if (!responses.isEmpty()) {
            for (SurveyAnswer answer : answerRepository.findAllByResponseIdIn(responses.stream().map(SurveyResponseEntry::getId).toList())) {
                answersByResponse.computeIfAbsent(answer.getResponseId(), key -> new HashMap<>())
                        .put(answer.getQuestionId(), readList(answer.getValueJson()));
            }
        }
        List<ResultRow> rows = responses.stream()
                .map(response -> new ResultRow(response.getFoodsharingId(), response.getRespondentName(), response.getCreatedAt(),
                        answersByResponse.getOrDefault(response.getId(), Map.of())))
                .toList();
        return new ResultsView(survey, questions, rows);
    }

    @Transactional(readOnly = true)
    public FormView form(UUID surveyId) {
        String foodsharingId = currentActorService.requireFoodsharingId();
        Survey survey = surveyRepository.findById(surveyId).orElseThrow(SurveyService::notFound);
        return new FormView(survey, questionRepository.findAllBySurveyIdOrderBySortOrderAsc(surveyId),
                responseRepository.existsBySurveyIdAndFoodsharingId(surveyId, foodsharingId));
    }

    @Transactional
    public void submit(UUID surveyId, List<AnswerInput> answers) {
        String foodsharingId = currentActorService.requireFoodsharingId();
        Survey survey = surveyRepository.findById(surveyId).orElseThrow(SurveyService::notFound);
        if (responseRepository.existsBySurveyIdAndFoodsharingId(surveyId, foodsharingId)) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.SURVEY_ALREADY_SUBMITTED);
        }
        List<SurveyQuestion> questions = questionRepository.findAllBySurveyIdOrderBySortOrderAsc(surveyId);
        Map<UUID, List<String>> cleaned = cleanAnswers(questions, answers == null ? List.of() : answers);

        SurveyResponseEntry response = new SurveyResponseEntry();
        response.setSurveyId(survey.getId());
        response.setFoodsharingId(foodsharingId);
        response.setRespondentName(userRepository.findByFoodsharingIdIgnoreCase(foodsharingId).map(User::getName).orElse(""));
        try {
            responseRepository.saveAndFlush(response);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.SURVEY_ALREADY_SUBMITTED);
        }
        cleaned.forEach((questionId, values) -> {
            SurveyAnswer answer = new SurveyAnswer();
            answer.setResponseId(response.getId());
            answer.setQuestionId(questionId);
            answer.setValueJson(writeList(values));
            answerRepository.save(answer);
        });
    }

    /** Checks every answer against its question; returns only the questions that were answered. */
    private Map<UUID, List<String>> cleanAnswers(List<SurveyQuestion> questions, List<AnswerInput> answers) {
        Map<UUID, SurveyQuestion> questionsById = new HashMap<>();
        questions.forEach(question -> questionsById.put(question.getId(), question));
        Map<UUID, List<String>> given = new HashMap<>();
        for (AnswerInput answer : answers) {
            if (!questionsById.containsKey(answer.questionId()) || given.containsKey(answer.questionId())) {
                throw invalid("answers.questionId");
            }
            List<String> values = new ArrayList<>();
            if (answer.values() != null) {
                for (String value : answer.values()) {
                    if (value != null && !value.isBlank()) {
                        values.add(value.trim());
                    }
                }
            }
            given.put(answer.questionId(), values);
        }

        Map<UUID, List<String>> cleaned = new HashMap<>();
        for (SurveyQuestion question : questions) {
            List<String> values = given.getOrDefault(question.getId(), List.of());
            if (values.isEmpty()) {
                if (question.isRequired()) {
                    throw invalid("answers." + question.getId());
                }
                continue;
            }
            List<String> options = readList(question.getOptionsJson());
            boolean valid = switch (question.getType()) {
                case TEXT -> values.size() == 1 && values.get(0).length() <= MAX_ANSWER_LENGTH;
                case STORE -> values.size() == 1 && values.get(0).length() <= MAX_STORE_LENGTH;
                case BOOLEAN -> values.size() == 1 && (values.get(0).equals("true") || values.get(0).equals("false"));
                case SINGLE_CHOICE -> values.size() == 1 && options.contains(values.get(0));
                case MULTIPLE_CHOICE -> new HashSet<>(values).size() == values.size() && options.containsAll(values);
            };
            if (!valid) {
                throw invalid("answers." + question.getId());
            }
            cleaned.put(question.getId(), values);
        }
        return cleaned;
    }

    private void validateDefinition(String title, List<QuestionInput> questions) {
        if (title == null || title.isBlank() || title.trim().length() > 200) {
            throw invalid("title");
        }
        if (questions == null || questions.isEmpty() || questions.size() > 100) {
            throw invalid("questions");
        }
        boolean hasStoreQuestion = false;
        for (QuestionInput question : questions) {
            if (question.type() == null || question.label() == null || question.label().isBlank() || question.label().trim().length() > 500) {
                throw invalid("questions.label");
            }
            if (question.type() == SurveyQuestionType.STORE) {
                if (hasStoreQuestion) {
                    throw invalid("questions.store");
                }
                hasStoreQuestion = true;
            }
            if (isChoice(question.type()) && cleanOptions(question).size() < 2) {
                throw invalid("questions.options");
            }
        }
    }

    private void applyQuestion(SurveyQuestion question, QuestionInput input, int index) {
        question.setSortOrder(index);
        question.setType(input.type());
        question.setLabel(input.label().trim());
        // The store always comes from the link, so a store question without a value makes no sense.
        question.setRequired(input.type() == SurveyQuestionType.STORE || input.required());
        question.setOptionsJson(writeList(isChoice(input.type()) ? cleanOptions(input) : List.of()));
    }

    private static boolean isChoice(SurveyQuestionType type) {
        return type == SurveyQuestionType.MULTIPLE_CHOICE || type == SurveyQuestionType.SINGLE_CHOICE;
    }

    private static List<String> cleanOptions(QuestionInput question) {
        Set<String> options = new LinkedHashSet<>();
        if (question.options() != null) {
            for (String option : question.options()) {
                if (option != null && !option.isBlank()) {
                    options.add(option.trim());
                }
            }
        }
        return List.copyOf(options);
    }

    /** Co-authors must be other active users with the survey permission. Returns the cleaned, de-duplicated ids. */
    private List<String> validateCoauthors(String owner, List<String> coauthorIds) {
        if (coauthorIds == null) {
            return List.of();
        }
        Map<String, String> cleaned = new java.util.LinkedHashMap<>();
        for (String id : coauthorIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String trimmed = id.trim();
            if (trimmed.equalsIgnoreCase(owner)) {
                continue;
            }
            User user = userRepository.findByFoodsharingIdIgnoreCaseAndActiveTrue(trimmed)
                    .filter(User::isCanManageSurveys)
                    .orElseThrow(() -> invalid("coauthorFoodsharingIds"));
            cleaned.putIfAbsent(user.getFoodsharingId().toLowerCase(Locale.ROOT), user.getFoodsharingId());
        }
        if (cleaned.size() > MAX_COAUTHORS) {
            throw invalid("coauthorFoodsharingIds");
        }
        return List.copyOf(cleaned.values());
    }

    private void saveCoauthors(UUID surveyId, List<String> coauthors) {
        for (String foodsharingId : coauthors) {
            SurveyCoauthor coauthor = new SurveyCoauthor();
            coauthor.setSurveyId(surveyId);
            coauthor.setFoodsharingId(foodsharingId);
            coauthorRepository.save(coauthor);
        }
    }

    private SurveyView view(Survey survey, String me, boolean withQuestions) {
        List<Person> coauthors = coauthorRepository.findAllBySurveyIdOrderByCreatedAtAsc(survey.getId()).stream()
                .map(coauthor -> person(coauthor.getFoodsharingId()))
                .toList();
        return new SurveyView(survey,
                withQuestions ? questionRepository.findAllBySurveyIdOrderBySortOrderAsc(survey.getId()) : List.of(),
                responseRepository.countBySurveyId(survey.getId()), isOwner(survey, me), person(survey.getOwnerFoodsharingId()), coauthors);
    }

    private Person person(String foodsharingId) {
        return new Person(foodsharingId, userRepository.findByFoodsharingIdIgnoreCase(foodsharingId).map(User::getName).orElse(""));
    }

    private static boolean isOwner(Survey survey, String foodsharingId) {
        return survey.getOwnerFoodsharingId().equalsIgnoreCase(foodsharingId);
    }

    private String requireManager() {
        currentActorService.requirePermission(ch.it4user.foodsharing.domain.enumtype.UserPermission.CAN_MANAGE_SURVEYS);
        return currentActorService.requireFoodsharingId();
    }

    /** Surveys of other users are reported as missing so that their existence is not revealed. */
    private Survey requireOwnSurvey(UUID surveyId) {
        String owner = requireManager();
        return surveyRepository.findById(surveyId)
                .filter(survey -> isOwner(survey, owner))
                .orElseThrow(SurveyService::notFound);
    }

    /** The owner or a co-author; still requires the survey permission so that revoking it also ends co-authorship. */
    private Survey requireAccessibleSurvey(UUID surveyId) {
        String me = requireManager();
        return surveyRepository.findById(surveyId)
                .filter(survey -> isOwner(survey, me) || coauthorRepository.existsBySurveyIdAndFoodsharingIdIgnoreCase(surveyId, me))
                .orElseThrow(SurveyService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.SURVEY_NOT_FOUND);
    }

    private static ApiException invalid(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of(field));
    }

    public List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored survey JSON", exception);
        }
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
