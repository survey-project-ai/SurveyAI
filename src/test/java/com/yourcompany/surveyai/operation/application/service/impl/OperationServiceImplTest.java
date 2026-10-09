package com.yourcompany.surveyai.operation.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yourcompany.surveyai.auth.application.RequestAuthContext;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.domain.entity.AppUser;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.common.repository.AppUserRepository;
import com.yourcompany.surveyai.common.repository.CompanyRepository;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsResponseDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationResponseDto;
import com.yourcompany.surveyai.operation.application.dto.request.CreateOperationRequest;
import com.yourcompany.surveyai.operation.application.support.OperationAutoEntityLexiconService;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.domain.enums.OperationStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.response.domain.entity.SurveyAnswer;
import com.yourcompany.surveyai.response.domain.entity.SurveyResponse;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import com.yourcompany.surveyai.response.repository.SurveyAnswerRepository;
import com.yourcompany.surveyai.response.repository.SurveyResponseRepository;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.domain.enums.SurveyStatus;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionOptionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Map;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OperationServiceImplTest {

    private final OperationRepository operationRepository = mock(OperationRepository.class);
    private final OperationContactRepository operationContactRepository = mock(OperationContactRepository.class);
    private final CallAttemptRepository callAttemptRepository = mock(CallAttemptRepository.class);
    private final CallJobRepository callJobRepository = mock(CallJobRepository.class);
    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final SurveyRepository surveyRepository = mock(SurveyRepository.class);
    private final SurveyQuestionRepository surveyQuestionRepository = mock(SurveyQuestionRepository.class);
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository = mock(SurveyQuestionOptionRepository.class);
    private final SurveyResponseRepository surveyResponseRepository = mock(SurveyResponseRepository.class);
    private final SurveyAnswerRepository surveyAnswerRepository = mock(SurveyAnswerRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final CallJobDispatcher callJobDispatcher = mock(CallJobDispatcher.class);
    private final RequestAuthContext requestAuthContext = new RequestAuthContext(mock(HttpServletRequest.class));
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OperationAutoEntityLexiconService operationAutoEntityLexiconService = new OperationAutoEntityLexiconService(objectMapper, null, null) {
        @Override
        public String buildSourcePayloadJson(
                Survey survey,
                String operationName,
                List<SurveyQuestion> questions,
                Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId,
                String existingSourcePayloadJson
        ) {
            lexiconBuildInvocations += 1;
            return nextOperationSourcePayloadJson;
        }
    };

    private OperationServiceImpl operationService;
    private String nextOperationSourcePayloadJson;
    private int lexiconBuildInvocations;

    @BeforeEach
    void setUp() {
        operationService = new OperationServiceImpl(
                operationRepository,
                operationContactRepository,
                callAttemptRepository,
                callJobRepository,
                companyRepository,
                surveyRepository,
                surveyQuestionRepository,
                surveyQuestionOptionRepository,
                surveyResponseRepository,
                surveyAnswerRepository,
                appUserRepository,
                callJobDispatcher,
                requestAuthContext,
                validator,
                objectMapper,
                operationAutoEntityLexiconService
        );

        when(operationRepository.save(any(Operation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        nextOperationSourcePayloadJson = "{\"autoEntityLexiconPreview\":{\"questionSuggestions\":[],\"keywords\":[]}}";
        lexiconBuildInvocations = 0;
    }

    @Test
    void createOperation_buildsOperationScopedAutoEntityLexiconPayload() {
        Company company = new Company();
        company.setId(UUID.randomUUID());

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setCompany(company);
        survey.setStatus(SurveyStatus.PUBLISHED);
        survey.setName("Secmen egilimleri");

        SurveyQuestion question = buildQuestion(survey, "vote", 1, QuestionType.SINGLE_CHOICE, "Bugun secim olsa kime oy verirsiniz?");
        SurveyQuestionOption option = buildOption(question, 1, "cemil_tugay", "Cemil Tugay", "Cemil Tugay");

        CreateOperationRequest request = new CreateOperationRequest();
        request.setSurveyId(survey.getId());
        request.setName("Izmir belediye secimleri");

        when(companyRepository.findById(company.getId())).thenReturn(Optional.of(company));
        when(surveyRepository.findByIdAndCompany_IdAndDeletedAtIsNull(survey.getId(), company.getId())).thenReturn(Optional.of(survey));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(survey.getId()))
                .thenReturn(List.of(question));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(List.of(question.getId())))
                .thenReturn(List.of(option));
        nextOperationSourcePayloadJson = "{\"autoEntityLexiconPreview\":{\"operationName\":\"Izmir belediye secimleri\",\"questionSuggestions\":[{\"questionCode\":\"vote\"}],\"keywords\":[\"Cemil Tugay\"]}}";
        AppUser createdBy = new AppUser();
        createdBy.setId(UUID.randomUUID());
        createdBy.setCompany(company);
        request.setCreatedByUserId(createdBy.getId());
        when(appUserRepository.findById(createdBy.getId())).thenReturn(Optional.of(createdBy));

        OperationResponseDto response = operationService.createOperation(company.getId(), request);

        assertThat(response.sourcePayloadJson()).contains("autoEntityLexiconPreview");
        assertThat(lexiconBuildInvocations).isEqualTo(1);
    }

    @Test
    void getOperationById_marksDraftAsReadyWhenPrerequisitesAreSatisfied() {
        Operation operation = buildOperation(OperationStatus.DRAFT, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();
        List<OperationContact> contacts = List.of(buildContact(operation), buildContact(operation));

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn((long) contacts.size());
        when(operationContactRepository.findAllByOperation_IdAndCompany_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId, companyId))
                .thenReturn(contacts);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId)).thenReturn(List.of());

        OperationResponseDto response = operationService.getOperationById(companyId, operationId);

        assertThat(response.status()).isEqualTo(OperationStatus.READY);
        assertThat(response.readiness().readyToStart()).isTrue();
        assertThat(response.readiness().blockingReasons()).isEmpty();
        verify(operationRepository).save(operation);
    }

    @Test
    void startOperation_marksOperationRunningAndPreparesCallJobs() {
        Operation operation = buildOperation(OperationStatus.READY, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();
        List<OperationContact> contacts = List.of(buildContact(operation), buildContact(operation));
        List<CallJob> savedJobs = new ArrayList<>();

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn((long) contacts.size());
        when(operationContactRepository.findAllByOperation_IdAndCompany_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId, companyId))
                .thenReturn(contacts);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenAnswer(invocation -> List.copyOf(savedJobs));
        when(callJobRepository.saveAll(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<CallJob> jobs = new ArrayList<>((List<CallJob>) invocation.getArgument(0));
            savedJobs.addAll(jobs);
            return jobs;
        });

        OperationResponseDto response = operationService.startOperation(companyId, operationId);

        assertThat(response.status()).isEqualTo(OperationStatus.RUNNING);
        assertThat(response.startedAt()).isNotNull();
        assertThat(response.executionSummary().newlyPreparedCallJobs()).isEqualTo(2);
        assertThat(response.executionSummary().totalCallJobs()).isEqualTo(2);
        assertThat(response.executionSummary().pendingCallJobs()).isEqualTo(2);
        assertThat(savedJobs)
                .hasSize(2)
                .allSatisfy(job -> {
                    assertThat(job.getStatus()).isEqualTo(CallJobStatus.PENDING);
                    assertThat(job.getScheduledFor()).isEqualTo(response.startedAt());
                    assertThat(job.getAvailableAt()).isEqualTo(response.startedAt());
                });
        verify(callJobDispatcher).dispatchNextPreparedJob(operationId);
    }

    @Test
    void startOperation_rejectsWhenContactsAreMissing() {
        Operation operation = buildOperation(OperationStatus.DRAFT, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(0L);
        when(operationContactRepository.findAllByOperation_IdAndCompany_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId, companyId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> operationService.startOperation(companyId, operationId))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("en az bir kisi gerekli");
    }

    @Test
    void deleteOperation_softDeletesOperationAndRelatedRecords() {
        Operation operation = buildOperation(OperationStatus.DRAFT, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();
        OffsetDateTime beforeDelete = OffsetDateTime.now().minusMinutes(1);

        OperationContact contact = buildContact(operation);
        CallJob callJob = new CallJob();
        callJob.setId(UUID.randomUUID());
        callJob.setOperation(operation);
        callJob.setOperationContact(contact);
        CallAttempt attempt = new CallAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setCallJob(callJob);
        SurveyResponse surveyResponse = buildSurveyResponse(operation, SurveyResponseStatus.COMPLETED, "905551112233", 100, OffsetDateTime.now());
        SurveyQuestion question = buildQuestion(operation.getSurvey(), "q-open", 1, QuestionType.OPEN_ENDED, "Yorumunuz");
        SurveyAnswer surveyAnswer = buildOpenEndedAnswer(surveyResponse, question, "Tamam");

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(callJobRepository.existsByOperation_IdAndStatusInAndDeletedAtIsNull(any(UUID.class), any()))
                .thenReturn(false);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId)).thenReturn(List.of(callJob));
        when(callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(callJob.getId())).thenReturn(List.of(attempt));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(surveyResponse));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(surveyResponse.getId())))
                .thenReturn(List.of(surveyAnswer));
        when(operationContactRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId)).thenReturn(List.of(contact));
        when(operationRepository.save(any(Operation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        operationService.deleteOperation(companyId, operationId);

        assertThat(operation.getDeletedAt()).isAfter(beforeDelete);
        assertThat(contact.getDeletedAt()).isAfter(beforeDelete);
        assertThat(callJob.getDeletedAt()).isAfter(beforeDelete);
        assertThat(attempt.getDeletedAt()).isAfter(beforeDelete);
        assertThat(surveyResponse.getDeletedAt()).isAfter(beforeDelete);
        assertThat(surveyAnswer.getDeletedAt()).isAfter(beforeDelete);
        verify(operationRepository).save(operation);
    }

    @Test
    void deleteOperation_rejectsPausedOperationWhileACallIsStillLive() {
        Operation operation = buildOperation(OperationStatus.PAUSED, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(callJobRepository.existsByOperation_IdAndStatusInAndUpdatedAtAfterAndDeletedAtIsNull(
                eq(operationId),
                eq(java.util.EnumSet.of(CallJobStatus.QUEUED, CallJobStatus.IN_PROGRESS)),
                any(OffsetDateTime.class)
        )).thenReturn(true);

        assertThatThrownBy(() -> operationService.deleteOperation(companyId, operationId))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Devam eden bir arama var");

        verify(operationRepository, never()).save(any(Operation.class));
    }

    @Test
    void deleteOperation_rejectsRunningOperation() {
        Operation operation = buildOperation(OperationStatus.RUNNING, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(callJobRepository.existsByOperation_IdAndStatusInAndDeletedAtIsNull(operationId, java.util.EnumSet.of(
                CallJobStatus.PENDING,
                CallJobStatus.QUEUED,
                CallJobStatus.IN_PROGRESS,
                CallJobStatus.RETRY
        ))).thenReturn(true);

        assertThatThrownBy(() -> operationService.deleteOperation(companyId, operationId))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Once duraklatin");
    }

    @Test
    void getOperationAnalytics_aggregatesRealSurveyResponsesAndAnswers() {
        Operation operation = buildOperation(OperationStatus.RUNNING, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion choiceQuestion = buildQuestion(operation.getSurvey(), "q-choice", 1, QuestionType.SINGLE_CHOICE, "Memnun musunuz?");
        SurveyQuestion ratingQuestion = buildQuestion(operation.getSurvey(), "q-rating", 2, QuestionType.RATING, "Deneyim puani");
        SurveyQuestion openEndedQuestion = buildQuestion(operation.getSurvey(), "q-open", 3, QuestionType.OPEN_ENDED, "Yorumunuz");

        SurveyQuestionOption yesOption = buildOption(choiceQuestion, 1, "YES", "Evet", "yes");
        SurveyQuestionOption noOption = buildOption(choiceQuestion, 2, "NO", "Hayir", "no");

        SurveyResponse completedResponse = buildSurveyResponse(operation, SurveyResponseStatus.COMPLETED, "905551112233", 100, OffsetDateTime.now().minusHours(2));
        SurveyResponse partialResponse = buildSurveyResponse(operation, SurveyResponseStatus.PARTIAL, "905551112244", 50, OffsetDateTime.now().minusHours(1));

        SurveyAnswer choiceAnswer = buildChoiceAnswer(completedResponse, choiceQuestion, yesOption);
        SurveyAnswer ratingAnswer = buildRatingAnswer(completedResponse, ratingQuestion, 5);
        SurveyAnswer openEndedAnswer = buildOpenEndedAnswer(
                partialResponse,
                openEndedQuestion,
                "Temsilci oldukca yardimciydi ve surec kolay ilerledi."
        );

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(3L);
        when(callJobRepository.countGroupedByStatusForOperation(operationId))
                .thenReturn(List.<Object[]>of(
                        new Object[]{CallJobStatus.COMPLETED, 1L},
                        new Object[]{CallJobStatus.FAILED, 1L},
                        new Object[]{CallJobStatus.QUEUED, 1L}
                ));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(partialResponse, completedResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(choiceQuestion, ratingQuestion, openEndedQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(choiceQuestion.getId(), ratingQuestion.getId(), openEndedQuestion.getId())
        )).thenReturn(List.of(yesOption, noOption));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(
                List.of(partialResponse.getId(), completedResponse.getId())
        )).thenReturn(List.of(choiceAnswer, ratingAnswer, openEndedAnswer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.totalContacts()).isEqualTo(3);
        assertThat(response.totalCallJobs()).isEqualTo(3);
        assertThat(response.totalCompletedCalls()).isEqualTo(1);
        assertThat(response.failedCallJobs()).isEqualTo(1);
        assertThat(response.totalResponses()).isEqualTo(2);
        assertThat(response.respondedContacts()).isEqualTo(2);
        assertThat(response.completedResponses()).isEqualTo(1);
        assertThat(response.partialResponses()).isEqualTo(1);
        assertThat(response.completionRate()).isEqualTo(50.0);
        assertThat(response.responseRate()).isEqualTo(66.7);
        assertThat(response.contactReachRate()).isEqualTo(66.7);
        assertThat(response.outcomeBreakdown())
                .extracting(item -> item.key() + ":" + item.count())
                .containsExactly(
                        "queued:1",
                        "inProgress:0",
                        "completed:1",
                        "failed:1",
                        "skipped:0"
                );
        assertThat(response.insightItems()).isNotEmpty();
        assertThat(response.questionSummaries())
                .extracting(item -> item.questionTitle())
                .containsExactly("Memnun musunuz?", "Deneyim puani", "Yorumunuz");
        assertThat(response.questionSummaries())
                .extracting(item -> item.respondedContactCount())
                .containsExactly(2L, 2L, 2L);
        assertThat(response.questionSummaries().get(2).sampleResponses())
                .extracting(item -> item.responseText())
                .containsExactly("Temsilci oldukca yardimciydi ve surec kolay ilerledi.");
    }

    @Test
    void getOperationAnalytics_doesNotCountZeroProgressPartialResponseAsAcceptedConsent() {
        Operation operation = buildOperation(OperationStatus.RUNNING, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyResponse voicemailLikeResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.PARTIAL,
                "905551112255",
                0,
                OffsetDateTime.now().minusMinutes(10)
        );

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(1L);
        when(callJobRepository.countGroupedByStatusForOperation(operationId))
                .thenReturn(List.<Object[]>of(new Object[]{CallJobStatus.FAILED, 1L}));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(voicemailLikeResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(voicemailLikeResponse.getId())))
                .thenReturn(List.of());

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.consentBreakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly(
                        "Katilmayi kabul etti:0",
                        "Katilmayi reddetti:0",
                        "Belirsiz / yanitsiz:1"
                );
    }

    @Test
    void getOperationAnalytics_recoversChoiceDistributionFromLegacyInvalidAnswers() {
        Operation operation = buildOperation(OperationStatus.RUNNING, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion choiceQuestion = buildQuestion(operation.getSurvey(), "q-choice", 1, QuestionType.SINGLE_CHOICE, "Mevcut belediyeye hiç oy verdiniz mi");
        SurveyQuestionOption yesOption = buildOption(choiceQuestion, 1, "option_1", "Evet", "option_1");
        SurveyQuestionOption noOption = buildOption(choiceQuestion, 2, "option_2", "Hayir", "option_2");

        SurveyResponse firstResponse = buildSurveyResponse(operation, SurveyResponseStatus.COMPLETED, "905551112233", 100, OffsetDateTime.now().minusHours(2));
        SurveyResponse secondResponse = buildSurveyResponse(operation, SurveyResponseStatus.COMPLETED, "905551112244", 100, OffsetDateTime.now().minusHours(1));

        SurveyAnswer yesAnswer = buildChoiceAnswer(firstResponse, choiceQuestion, yesOption);
        SurveyAnswer legacyNoAnswer = new SurveyAnswer();
        legacyNoAnswer.setId(UUID.randomUUID());
        legacyNoAnswer.setCompany(secondResponse.getCompany());
        legacyNoAnswer.setSurveyResponse(secondResponse);
        legacyNoAnswer.setSurveyQuestion(choiceQuestion);
        legacyNoAnswer.setAnswerType(QuestionType.SINGLE_CHOICE);
        legacyNoAnswer.setAnswerText("Hayır");
        legacyNoAnswer.setRawInputText("Hayır");
        legacyNoAnswer.setAnswerJson("{}");
        legacyNoAnswer.setValid(false);
        legacyNoAnswer.setInvalidReason("Choice answer did not match an option");
        legacyNoAnswer.setRetryCount(0);

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(2L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(
                        buildCallJob(operation, CallJobStatus.COMPLETED),
                        buildCallJob(operation, CallJobStatus.COMPLETED)
                ));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(secondResponse, firstResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(choiceQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(choiceQuestion.getId())
        )).thenReturn(List.of(yesOption, noOption));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(
                List.of(secondResponse.getId(), firstResponse.getId())
        )).thenReturn(List.of(yesAnswer, legacyNoAnswer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionSummaries()).hasSize(1);
        assertThat(response.questionSummaries().getFirst().answeredCount()).isEqualTo(2);
        assertThat(response.questionSummaries().getFirst().breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("Evet:1", "Hayir:1");
        assertThat(response.insightItems())
                .extracting(item -> item.detail())
                .filteredOn(detail -> ((String) detail).contains("en cok secilen cevap"))
                .containsExactly("\"Mevcut belediyeye hiç oy verdiniz mi\" sorusunda en cok secilen cevap Evet oldu (%50.0).");
    }

    @Test
    void getOperationAnalytics_ignoresChoiceAnswersWithNullFallbackText() {
        Operation operation = buildOperation(OperationStatus.RUNNING, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion choiceQuestion = buildQuestion(
                operation.getSurvey(),
                "q-choice",
                1,
                QuestionType.SINGLE_CHOICE,
                "Mevcut belediyeye oy verdiniz mi"
        );
        SurveyQuestionOption yesOption = buildOption(choiceQuestion, 1, "option_1", "Evet", "option_1");
        SurveyQuestionOption noOption = buildOption(choiceQuestion, 2, "option_2", "Hayir", "option_2");

        SurveyResponse firstResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112233",
                100,
                OffsetDateTime.now().minusHours(2)
        );
        SurveyResponse secondResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.PARTIAL,
                "905551112244",
                25,
                OffsetDateTime.now().minusHours(1)
        );

        SurveyAnswer yesAnswer = buildChoiceAnswer(firstResponse, choiceQuestion, yesOption);
        SurveyAnswer blankChoiceAnswer = new SurveyAnswer();
        blankChoiceAnswer.setId(UUID.randomUUID());
        blankChoiceAnswer.setCompany(secondResponse.getCompany());
        blankChoiceAnswer.setSurveyResponse(secondResponse);
        blankChoiceAnswer.setSurveyQuestion(choiceQuestion);
        blankChoiceAnswer.setAnswerType(QuestionType.SINGLE_CHOICE);
        blankChoiceAnswer.setAnswerText(null);
        blankChoiceAnswer.setRawInputText(null);
        blankChoiceAnswer.setAnswerJson("{}");
        blankChoiceAnswer.setValid(false);
        blankChoiceAnswer.setInvalidReason("Choice answer did not match an option");
        blankChoiceAnswer.setRetryCount(0);

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(2L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(
                        buildCallJob(operation, CallJobStatus.COMPLETED),
                        buildCallJob(operation, CallJobStatus.FAILED)
                ));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(secondResponse, firstResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(choiceQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(choiceQuestion.getId())
        )).thenReturn(List.of(yesOption, noOption));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(
                List.of(secondResponse.getId(), firstResponse.getId())
        )).thenReturn(List.of(yesAnswer, blankChoiceAnswer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionSummaries()).hasSize(1);
        assertThat(response.questionSummaries().getFirst().answeredCount()).isEqualTo(1);
        assertThat(response.questionSummaries().getFirst().breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("Evet:1", "Hayir:0");
    }

    @Test
    void getOperationAnalytics_includesSpecialAnswersInQuestionSummary() {
        Operation operation = buildOperation(OperationStatus.RUNNING, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion choiceQuestion = buildQuestion(
                operation.getSurvey(),
                "q-choice",
                1,
                QuestionType.SINGLE_CHOICE,
                "Bu konuda fikriniz var mi?"
        );
        SurveyQuestionOption yesOption = buildOption(choiceQuestion, 1, "option_1", "Evet", "option_1");
        SurveyQuestionOption noOption = buildOption(choiceQuestion, 2, "option_2", "Hayir", "option_2");

        SurveyResponse completedResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112288",
                100,
                OffsetDateTime.now().minusMinutes(8)
        );

        SurveyAnswer specialAnswer = new SurveyAnswer();
        specialAnswer.setId(UUID.randomUUID());
        specialAnswer.setCompany(operation.getCompany());
        specialAnswer.setSurveyResponse(completedResponse);
        specialAnswer.setSurveyQuestion(choiceQuestion);
        specialAnswer.setAnswerType(QuestionType.SINGLE_CHOICE);
        specialAnswer.setAnswerText("Bilmiyorum");
        specialAnswer.setRawInputText("Bilmiyorum");
        specialAnswer.setAnswerJson("""
                {
                  "specialAnswerCode": "bilmiyorum",
                  "normalizedText": "Bilmiyorum"
                }
                """);
        specialAnswer.setValid(true);
        specialAnswer.setRetryCount(0);

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(1L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(buildCallJob(operation, CallJobStatus.COMPLETED)));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(completedResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(choiceQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(choiceQuestion.getId())
        )).thenReturn(List.of(yesOption, noOption));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(completedResponse.getId())))
                .thenReturn(List.of(specialAnswer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionSummaries()).hasSize(1);
        assertThat(response.questionSummaries().getFirst().answeredCount()).isEqualTo(1);
        assertThat(response.questionSummaries().getFirst().breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("Evet:0", "Hayir:0");
        assertThat(response.questionSummaries().getFirst().specialAnswerBreakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("Bilmiyorum:1");
    }

    @Test
    void getOperationAnalytics_groupsOpenEndedAnswersAutomaticallyAndKeepsRawSamples() {
        Operation operation = buildOperation(OperationStatus.RUNNING, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion openEndedQuestion = buildQuestion(
                operation.getSurvey(),
                "q-open",
                1,
                QuestionType.OPEN_ENDED,
                "Bu sehirde en onemli sorun nedir?"
        );
        openEndedQuestion.setSettingsJson("""
                {
                  "codingCategories": {
                    "ulasim": ["ulasim", "trafik"],
                    "ekonomi": ["issizlik", "ekonomi"]
                  }
                }
                """);

        SurveyResponse firstResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112301",
                100,
                OffsetDateTime.now().minusMinutes(30)
        );
        SurveyResponse secondResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112302",
                100,
                OffsetDateTime.now().minusMinutes(20)
        );
        SurveyResponse thirdResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112303",
                100,
                OffsetDateTime.now().minusMinutes(10)
        );

        SurveyAnswer firstAnswer = buildOpenEndedAnswer(firstResponse, openEndedQuestion, "Ulasim cok kotu.");
        SurveyAnswer secondAnswer = buildOpenEndedAnswer(thirdResponse, openEndedQuestion, "Trafik artik cekilmiyor.");
        SurveyAnswer thirdAnswer = buildOpenEndedAnswer(secondResponse, openEndedQuestion, "Parklar daha temiz olsun.");

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(3L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(
                        buildCallJob(operation, CallJobStatus.COMPLETED),
                        buildCallJob(operation, CallJobStatus.COMPLETED),
                        buildCallJob(operation, CallJobStatus.COMPLETED)
                ));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(thirdResponse, secondResponse, firstResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(openEndedQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(openEndedQuestion.getId())
        )).thenReturn(List.of());
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(
                List.of(thirdResponse.getId(), secondResponse.getId(), firstResponse.getId())
        )).thenReturn(List.of(firstAnswer, secondAnswer, thirdAnswer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionSummaries()).hasSize(1);
        assertThat(response.questionSummaries().getFirst().answeredCount()).isEqualTo(3);
        assertThat(response.questionSummaries().getFirst().breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("Ulasim:2");
        assertThat(response.questionSummaries().getFirst().reviewCount()).isEqualTo(1);
        assertThat(response.questionSummaries().getFirst().sampleResponses())
                .extracting(item -> item.rawResponseText())
                .containsExactly("Parklar daha temiz olsun.");
    }

    @Test
    void getOperationAnalytics_usesKeywordFallbackWhenOpenEndedCategoriesMissing() {
        Operation operation = buildOperation(OperationStatus.RUNNING, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion openEndedQuestion = buildQuestion(
                operation.getSurvey(),
                "q-open-missing-categories",
                1,
                QuestionType.OPEN_ENDED,
                "Izmir icin oncelikli beklentiniz nedir?"
        );
        openEndedQuestion.setSettingsJson("{\"builderType\":\"short_text\"}");

        SurveyResponse firstResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112311",
                100,
                OffsetDateTime.now().minusMinutes(20)
        );
        SurveyResponse secondResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112312",
                100,
                OffsetDateTime.now().minusMinutes(10)
        );

        SurveyAnswer firstAnswer = buildOpenEndedAnswer(firstResponse, openEndedQuestion, "Ulasim sorunu cozulmeli.");
        SurveyAnswer secondAnswer = buildOpenEndedAnswer(secondResponse, openEndedQuestion, "Daha iyi olsun.");

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(2L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(
                        buildCallJob(operation, CallJobStatus.COMPLETED),
                        buildCallJob(operation, CallJobStatus.COMPLETED)
                ));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(secondResponse, firstResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(openEndedQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(openEndedQuestion.getId())
        )).thenReturn(List.of());
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(
                List.of(secondResponse.getId(), firstResponse.getId())
        )).thenReturn(List.of(firstAnswer, secondAnswer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionSummaries()).hasSize(1);
        assertThat(response.questionSummaries().getFirst().breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("Ulasim:1");
        assertThat(response.questionSummaries().getFirst().reviewCount()).isEqualTo(1);
        assertThat(response.questionSummaries().getFirst().sampleResponses())
                .extracting(item -> item.rawResponseText())
                .containsExactly("Daha iyi olsun.");
    }

    @Test
    void getOperationAnalytics_buildsNamedEntityDistributionForOpenEndedNameQuestions() {
        Operation operation = buildOperation(OperationStatus.RUNNING, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion openEndedQuestion = buildQuestion(
                operation.getSurvey(),
                "q-open-named-entity",
                1,
                QuestionType.OPEN_ENDED,
                "Izmirdeki siyasetciler denince akliniza ilk gelen, en begendiginiz siyasetcinin kim oldugunu soyler misiniz"
        );
        openEndedQuestion.setSettingsJson("""
                {
                  "builderType": "short_text",
                  "autoLexicon": {
                    "enabled": true
                  }
                }
                """);

        SurveyResponse firstResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112321",
                100,
                OffsetDateTime.now().minusMinutes(20)
        );
        SurveyResponse secondResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112322",
                100,
                OffsetDateTime.now().minusMinutes(10)
        );
        SurveyResponse thirdResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112323",
                100,
                OffsetDateTime.now().minusMinutes(5)
        );

        SurveyAnswer firstAnswer = buildOpenEndedAnswer(firstResponse, openEndedQuestion, "Levent Uysal.");
        SurveyAnswer secondAnswer = buildOpenEndedAnswer(secondResponse, openEndedQuestion, "Ali Mahir Basarir.");
        SurveyAnswer thirdAnswer = buildOpenEndedAnswer(thirdResponse, openEndedQuestion, "Levent Uysal");

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(3L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(
                        buildCallJob(operation, CallJobStatus.COMPLETED),
                        buildCallJob(operation, CallJobStatus.COMPLETED),
                        buildCallJob(operation, CallJobStatus.COMPLETED)
                ));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(thirdResponse, secondResponse, firstResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(openEndedQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(openEndedQuestion.getId())
        )).thenReturn(List.of());
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(
                List.of(thirdResponse.getId(), secondResponse.getId(), firstResponse.getId())
        )).thenReturn(List.of(firstAnswer, secondAnswer, thirdAnswer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionSummaries()).hasSize(1);
        assertThat(response.questionSummaries().getFirst().answeredCount()).isEqualTo(3);
        assertThat(response.questionSummaries().getFirst().reviewCount()).isZero();
        assertThat(response.questionSummaries().getFirst().breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("Levent Uysal:2", "Ali Mahir Basarir:1");
        assertThat(response.questionSummaries().getFirst().rawResponses())
                .extracting(item -> item.responseText())
                .containsExactly("Levent Uysal", "Ali Mahir Basarir.", "Levent Uysal.");
    }

    @Test
    void getOperationAnalytics_handlesEmptyStateAndLowDataScenarios() {
        Operation operation = buildOperation(OperationStatus.READY, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion question = buildQuestion(operation.getSurvey(), "q-choice", 1, QuestionType.SINGLE_CHOICE, "Katilir misiniz?");
        SurveyQuestionOption yesOption = buildOption(question, 1, "yes", "Evet", "yes");
        SurveyQuestionOption noOption = buildOption(question, 2, "no", "Hayir", "no");

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(1L);
        when(callJobRepository.countGroupedByStatusForOperation(operationId))
                .thenReturn(List.<Object[]>of(new Object[]{CallJobStatus.FAILED, 1L}));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of());
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(question));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(question.getId())
        )).thenReturn(List.of(yesOption, noOption));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.totalContacts()).isEqualTo(1);
        assertThat(response.totalResponses()).isZero();
        assertThat(response.completedResponses()).isZero();
        assertThat(response.failedCallJobs()).isEqualTo(1);
        assertThat(response.completionRate()).isZero();
        assertThat(response.responseRate()).isZero();
        assertThat(response.averageCompletionPercent()).isZero();
        assertThat(response.partialData()).isTrue();
        assertThat(response.questionSummaries()).hasSize(1);
        assertThat(response.questionSummaries().getFirst().answeredCount()).isZero();
        assertThat(response.questionSummaries().getFirst().emptyStateMessage()).isNotBlank();
        assertThat(response.questionSummaries().getFirst().breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("Evet:0", "Hayir:0");
    }

    @Test
    void getOperationAnalytics_usesOnlyUsableAnswersFromPartialResponses() {
        Operation operation = buildOperation(OperationStatus.RUNNING, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion choiceQuestion = buildQuestion(operation.getSurvey(), "q-binary", 1, QuestionType.SINGLE_CHOICE, "Devam edelim mi?");
        SurveyQuestion ratingQuestion = buildQuestion(operation.getSurvey(), "q-rating", 2, QuestionType.RATING, "Memnuniyet puani");
        SurveyQuestion openEndedQuestion = buildQuestion(operation.getSurvey(), "q-open", 3, QuestionType.OPEN_ENDED, "Ek yorum");
        SurveyQuestionOption yesOption = buildOption(choiceQuestion, 1, "yes", "Evet", "yes");
        SurveyQuestionOption noOption = buildOption(choiceQuestion, 2, "no", "Hayir", "no");

        SurveyResponse partialResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.PARTIAL,
                "905551112255",
                34,
                OffsetDateTime.now().minusMinutes(30)
        );

        SurveyAnswer validChoiceAnswer = buildChoiceAnswer(partialResponse, choiceQuestion, yesOption);

        SurveyAnswer invalidRatingAnswer = new SurveyAnswer();
        invalidRatingAnswer.setId(UUID.randomUUID());
        invalidRatingAnswer.setCompany(partialResponse.getCompany());
        invalidRatingAnswer.setSurveyResponse(partialResponse);
        invalidRatingAnswer.setSurveyQuestion(ratingQuestion);
        invalidRatingAnswer.setAnswerType(QuestionType.RATING);
        invalidRatingAnswer.setAnswerJson("{}");
        invalidRatingAnswer.setValid(false);
        invalidRatingAnswer.setInvalidReason("Rating answer could not be normalized");
        invalidRatingAnswer.setRetryCount(0);

        SurveyAnswer invalidOpenEndedAnswer = new SurveyAnswer();
        invalidOpenEndedAnswer.setId(UUID.randomUUID());
        invalidOpenEndedAnswer.setCompany(partialResponse.getCompany());
        invalidOpenEndedAnswer.setSurveyResponse(partialResponse);
        invalidOpenEndedAnswer.setSurveyQuestion(openEndedQuestion);
        invalidOpenEndedAnswer.setAnswerType(QuestionType.OPEN_ENDED);
        invalidOpenEndedAnswer.setAnswerText("  ");
        invalidOpenEndedAnswer.setRawInputText("  ");
        invalidOpenEndedAnswer.setAnswerJson("{}");
        invalidOpenEndedAnswer.setValid(false);
        invalidOpenEndedAnswer.setInvalidReason("Empty transcript");
        invalidOpenEndedAnswer.setRetryCount(0);

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(1L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(buildCallJob(operation, CallJobStatus.COMPLETED)));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(partialResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(choiceQuestion, ratingQuestion, openEndedQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(choiceQuestion.getId(), ratingQuestion.getId(), openEndedQuestion.getId())
        )).thenReturn(List.of(yesOption, noOption));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(partialResponse.getId())))
                .thenReturn(List.of(validChoiceAnswer, invalidRatingAnswer, invalidOpenEndedAnswer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.totalResponses()).isEqualTo(1);
        assertThat(response.respondedContacts()).isEqualTo(1);
        assertThat(response.completionRate()).isZero();
        assertThat(response.responseRate()).isEqualTo(100.0);
        assertThat(response.questionSummaries()).hasSize(3);
        assertThat(response.questionSummaries())
                .extracting(item -> item.respondedContactCount())
                .containsExactly(1L, 1L, 1L);
        assertThat(response.questionSummaries().get(0).answeredCount()).isEqualTo(1);
        assertThat(response.questionSummaries().get(0).breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("Evet:1", "Hayir:0");
        assertThat(response.questionSummaries().get(1).answeredCount()).isZero();
        assertThat(response.questionSummaries().get(1).averageRating()).isEqualTo(0.0);
        assertThat(response.questionSummaries().get(1).emptyStateMessage()).isNotBlank();
        assertThat(response.questionSummaries().get(2).answeredCount()).isZero();
        assertThat(response.questionSummaries().get(2).sampleResponses()).isEmpty();
        assertThat(response.questionSummaries().get(2).emptyStateMessage()).isNotBlank();
    }

    @Test
    void getOperationAnalytics_readsMultiChoiceSelectionsFromNormalizedAnswerJson() {
        Operation operation = buildOperation(OperationStatus.RUNNING, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion multiChoiceQuestion = buildQuestion(
                operation.getSurvey(),
                "q-multi",
                1,
                QuestionType.MULTI_CHOICE,
                "Hangi hizmetleri kullandınız?"
        );
        SurveyQuestionOption billingOption = buildOption(multiChoiceQuestion, 1, "billing", "Faturalama", "billing");
        SurveyQuestionOption supportOption = buildOption(multiChoiceQuestion, 2, "support", "Destek", "support");
        SurveyQuestionOption appOption = buildOption(multiChoiceQuestion, 3, "app", "Mobil Uygulama", "app");

        SurveyResponse completedResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112266",
                100,
                OffsetDateTime.now().minusMinutes(10)
        );

        SurveyAnswer multiChoiceAnswer = new SurveyAnswer();
        multiChoiceAnswer.setId(UUID.randomUUID());
        multiChoiceAnswer.setCompany(operation.getCompany());
        multiChoiceAnswer.setSurveyResponse(completedResponse);
        multiChoiceAnswer.setSurveyQuestion(multiChoiceQuestion);
        multiChoiceAnswer.setAnswerType(QuestionType.MULTI_CHOICE);
        multiChoiceAnswer.setAnswerJson("""
                {
                  "normalizedValues": ["billing", "support"],
                  "normalizedText": "billing, support"
                }
                """);
        multiChoiceAnswer.setValid(true);
        multiChoiceAnswer.setRetryCount(0);

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(1L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(buildCallJob(operation, CallJobStatus.COMPLETED)));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(completedResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(multiChoiceQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(multiChoiceQuestion.getId())
        )).thenReturn(List.of(billingOption, supportOption, appOption));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(completedResponse.getId())))
                .thenReturn(List.of(multiChoiceAnswer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionSummaries()).hasSize(1);
        assertThat(response.questionSummaries().getFirst().answeredCount()).isEqualTo(1);
        assertThat(response.questionSummaries().getFirst().breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("Faturalama:1", "Destek:1", "Mobil Uygulama:0");
    }

    @Test
    void getOperationAnalytics_readsRatingAndOpenEndedFallbacksFromAnswerJson() {
        Operation operation = buildOperation(OperationStatus.COMPLETED, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion ratingQuestion = buildQuestion(operation.getSurvey(), "q-rating", 1, QuestionType.RATING, "Genel puan");
        SurveyQuestion openEndedQuestion = buildQuestion(operation.getSurvey(), "q-open", 2, QuestionType.OPEN_ENDED, "Ek yorum");
        SurveyResponse completedResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112277",
                100,
                OffsetDateTime.now().minusMinutes(20)
        );

        SurveyAnswer ratingAnswer = new SurveyAnswer();
        ratingAnswer.setId(UUID.randomUUID());
        ratingAnswer.setCompany(operation.getCompany());
        ratingAnswer.setSurveyResponse(completedResponse);
        ratingAnswer.setSurveyQuestion(ratingQuestion);
        ratingAnswer.setAnswerType(QuestionType.RATING);
        ratingAnswer.setAnswerJson("""
                {
                  "normalizedNumber": 4
                }
                """);
        ratingAnswer.setValid(true);
        ratingAnswer.setRetryCount(0);

        SurveyAnswer openEndedAnswer = new SurveyAnswer();
        openEndedAnswer.setId(UUID.randomUUID());
        openEndedAnswer.setCompany(operation.getCompany());
        openEndedAnswer.setSurveyResponse(completedResponse);
        openEndedAnswer.setSurveyQuestion(openEndedQuestion);
        openEndedAnswer.setAnswerType(QuestionType.OPEN_ENDED);
        openEndedAnswer.setAnswerJson("""
                {
                  "normalizedText": "Süreç hızlı ve anlaşılırdı."
                }
                """);
        openEndedAnswer.setValid(true);
        openEndedAnswer.setRetryCount(0);

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(1L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(buildCallJob(operation, CallJobStatus.COMPLETED)));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(completedResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(ratingQuestion, openEndedQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(ratingQuestion.getId(), openEndedQuestion.getId())
        )).thenReturn(List.of());
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(completedResponse.getId())))
                .thenReturn(List.of(ratingAnswer, openEndedAnswer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionSummaries()).hasSize(2);
        assertThat(response.questionSummaries().get(0).answeredCount()).isEqualTo(1);
        assertThat(response.questionSummaries().get(0).averageRating()).isEqualTo(4.0);
        assertThat(response.questionSummaries().get(0).breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("4:1");
        assertThat(response.questionSummaries().get(1).answeredCount()).isEqualTo(1);
        assertThat(response.questionSummaries().get(1).sampleResponses())
                .extracting(item -> item.responseText())
                .containsExactly("Süreç hızlı ve anlaşılırdı.");
    }

    @Test
    void getOperationAnalytics_buildsGroupedChoiceChartsFromQuestionMetadata() {
        Operation operation = buildOperation(OperationStatus.COMPLETED, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion politicianOne = buildQuestion(operation.getSurvey(), "B2_1", 1, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece taniyorsunuz?");
        politicianOne.setSettingsJson("""
                {
                  "groupCode": "B2",
                  "groupTitle": "Siyasetcileri ne derece taniyorsunuz?",
                  "rowLabel": "Levent Uysal",
                  "rowKey": "levent_uysal",
                  "optionSetCode": "familiarity_5"
                }
                """);
        SurveyQuestion politicianTwo = buildQuestion(operation.getSurvey(), "B2_2", 2, QuestionType.SINGLE_CHOICE, "Ali Mahir Basarir'i ne derece taniyorsunuz?");
        politicianTwo.setSettingsJson("""
                {
                  "groupCode": "B2",
                  "groupTitle": "Siyasetcileri ne derece taniyorsunuz?",
                  "rowLabel": "Ali Mahir Basarir",
                  "rowKey": "ali_mahir_basarir",
                  "optionSetCode": "familiarity_5"
                }
                """);

        SurveyQuestionOption veryWellOne = buildOption(politicianOne, 1, "cok_iyi_taniyorum", "Cok iyi taniyorum", "cok_iyi_taniyorum");
        SurveyQuestionOption knowOne = buildOption(politicianOne, 2, "taniyorum", "Taniyorum", "taniyorum");
        SurveyQuestionOption littleOne = buildOption(politicianOne, 3, "biraz_taniyorum", "Biraz taniyorum", "biraz_taniyorum");
        SurveyQuestionOption heardOne = buildOption(politicianOne, 4, "duydum_ama_tanimiyorum", "Duydum ama tanimiyorum", "duydum_ama_tanimiyorum");
        SurveyQuestionOption neverOne = buildOption(politicianOne, 5, "hic_duymadim", "Hic duymadim", "hic_duymadim");

        SurveyQuestionOption veryWellTwo = buildOption(politicianTwo, 1, "cok_iyi_taniyorum", "Cok iyi taniyorum", "cok_iyi_taniyorum");
        SurveyQuestionOption knowTwo = buildOption(politicianTwo, 2, "taniyorum", "Taniyorum", "taniyorum");
        SurveyQuestionOption littleTwo = buildOption(politicianTwo, 3, "biraz_taniyorum", "Biraz taniyorum", "biraz_taniyorum");
        SurveyQuestionOption heardTwo = buildOption(politicianTwo, 4, "duydum_ama_tanimiyorum", "Duydum ama tanimiyorum", "duydum_ama_tanimiyorum");
        SurveyQuestionOption neverTwo = buildOption(politicianTwo, 5, "hic_duymadim", "Hic duymadim", "hic_duymadim");

        SurveyResponse firstResponse = buildSurveyResponse(operation, SurveyResponseStatus.COMPLETED, "905551112233", 100, OffsetDateTime.now().minusHours(2));
        SurveyResponse secondResponse = buildSurveyResponse(operation, SurveyResponseStatus.COMPLETED, "905551112244", 100, OffsetDateTime.now().minusHours(1));

        SurveyAnswer firstRowOne = buildChoiceAnswer(firstResponse, politicianOne, knowOne);
        SurveyAnswer firstRowTwo = buildChoiceAnswer(firstResponse, politicianTwo, neverTwo);
        SurveyAnswer secondRowOne = buildChoiceAnswer(secondResponse, politicianOne, heardOne);
        SurveyAnswer secondRowTwo = buildChoiceAnswer(secondResponse, politicianTwo, knowTwo);

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(2L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(
                        buildCallJob(operation, CallJobStatus.COMPLETED),
                        buildCallJob(operation, CallJobStatus.COMPLETED)
                ));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(secondResponse, firstResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(politicianOne, politicianTwo));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(politicianOne.getId(), politicianTwo.getId())
        )).thenReturn(List.of(
                veryWellOne, knowOne, littleOne, heardOne, neverOne,
                veryWellTwo, knowTwo, littleTwo, heardTwo, neverTwo
        ));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(
                List.of(secondResponse.getId(), firstResponse.getId())
        )).thenReturn(List.of(firstRowOne, firstRowTwo, secondRowOne, secondRowTwo));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionGroups()).hasSize(1);
        assertThat(response.questionGroups().getFirst().groupCode()).isEqualTo("B2");
        assertThat(response.questionGroups().getFirst().groupTitle()).isEqualTo("Siyasetcileri ne derece taniyorsunuz?");
        assertThat(response.questionGroups().getFirst().rows())
                .extracting(item -> item.rowLabel() + ":" + item.answeredCount())
                .containsExactly("Levent Uysal:2", "Ali Mahir Basarir:2");
        assertThat(response.questionGroups().getFirst().series())
                .extracting(item -> item.label() + ":" + item.data())
                .containsExactly(
                        "Cok iyi taniyorum:[0, 0]",
                        "Taniyorum:[1, 1]",
                        "Biraz taniyorum:[0, 0]",
                        "Duydum ama tanimiyorum:[1, 0]",
                        "Hic duymadim:[0, 1]"
                );
    }

    @Test
    void getOperationAnalytics_buildsGroupedChartsForRatingMatrixQuestions() {
        Operation operation = buildOperation(OperationStatus.COMPLETED, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion trust = buildQuestion(operation.getSurvey(), "C1_1", 1, QuestionType.RATING, "Guvenilirlik");
        trust.setSettingsJson("""
                {
                  "groupCode": "C1",
                  "groupTitle": "Bir siyasetci icin hangi ozellikler onemlidir?",
                  "rowLabel": "Guvenilirlik",
                  "rowKey": "guvenilirlik"
                }
                """);
        SurveyQuestion leadership = buildQuestion(operation.getSurvey(), "C1_2", 2, QuestionType.RATING, "Liderlik");
        leadership.setSettingsJson("""
                {
                  "groupCode": "C1",
                  "groupTitle": "Bir siyasetci icin hangi ozellikler onemlidir?",
                  "rowLabel": "Liderlik",
                  "rowKey": "liderlik"
                }
                """);

        SurveyQuestionOption veryImportantTrust = buildOption(trust, 1, "cok_onemli", "Cok onemli", "5");
        SurveyQuestionOption importantTrust = buildOption(trust, 2, "onemli", "Onemli", "4");
        SurveyQuestionOption veryImportantLeadership = buildOption(leadership, 1, "cok_onemli", "Cok onemli", "5");
        SurveyQuestionOption importantLeadership = buildOption(leadership, 2, "onemli", "Onemli", "4");

        SurveyResponse firstResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551111111",
                100,
                OffsetDateTime.now().minusMinutes(30)
        );
        SurveyResponse secondResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905552222222",
                100,
                OffsetDateTime.now().minusMinutes(10)
        );

        SurveyAnswer firstTrust = buildRatingAnswer(firstResponse, trust, 5);
        SurveyAnswer firstLeadership = buildRatingAnswer(firstResponse, leadership, 4);
        SurveyAnswer secondTrust = buildRatingAnswer(secondResponse, trust, 4);
        SurveyAnswer secondLeadership = buildRatingAnswer(secondResponse, leadership, 5);

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(2L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(
                        buildCallJob(operation, CallJobStatus.COMPLETED),
                        buildCallJob(operation, CallJobStatus.COMPLETED)
                ));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(secondResponse, firstResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(trust, leadership));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(trust.getId(), leadership.getId())
        )).thenReturn(List.of(
                veryImportantTrust, importantTrust,
                veryImportantLeadership, importantLeadership
        ));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(
                List.of(secondResponse.getId(), firstResponse.getId())
        )).thenReturn(List.of(firstTrust, firstLeadership, secondTrust, secondLeadership));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionGroups()).hasSize(1);
        assertThat(response.questionGroups().getFirst().groupCode()).isEqualTo("C1");
        assertThat(response.questionGroups().getFirst().rows())
                .extracting(item -> item.rowLabel() + ":" + item.answeredCount())
                .containsExactly("Guvenilirlik:2", "Liderlik:2");
        assertThat(response.questionGroups().getFirst().series())
                .extracting(item -> item.label() + ":" + item.data())
                .containsExactly(
                        "Cok onemli:[1, 1]",
                        "Onemli:[1, 1]"
                );
    }

    @Test
    void getOperationAnalytics_matchesGroupedChoiceAnswersUsingTurkishAliasesAndPunctuation() {
        Operation operation = buildOperation(OperationStatus.COMPLETED, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion politicianOne = buildQuestion(operation.getSurvey(), "B2_1", 1, QuestionType.SINGLE_CHOICE, "Levent Uysal");
        politicianOne.setSettingsJson("""
                {
                  "groupCode": "B2",
                  "groupTitle": "Siyasetcileri ne derece taniyorsunuz?",
                  "rowLabel": "Levent Uysal",
                  "rowKey": "levent_uysal",
                  "aliases": {
                    "Tan?m?yorum": ["Tanımıyorum", "Tanımıyorum.", "tanimiyorum"]
                  }
                }
                """);
        SurveyQuestion politicianTwo = buildQuestion(operation.getSurvey(), "B2_2", 2, QuestionType.SINGLE_CHOICE, "Ali Mahir Basarir");
        politicianTwo.setSettingsJson("""
                {
                  "groupCode": "B2",
                  "groupTitle": "Siyasetcileri ne derece taniyorsunuz?",
                  "rowLabel": "Ali Mahir Basarir",
                  "rowKey": "ali_mahir_basarir",
                  "aliases": {
                    "Tan?m?yorum": ["Tanımıyorum", "Tanımıyorum.", "tanimiyorum"]
                  }
                }
                """);

        SurveyQuestionOption knowOne = buildOption(politicianOne, 1, "option_1", "Tanıyorum", "taniyorum");
        SurveyQuestionOption unknownOne = buildOption(politicianOne, 2, "option_3", "Tan?m?yorum", "tanimiyorum");
        SurveyQuestionOption knowTwo = buildOption(politicianTwo, 1, "option_1", "Tanıyorum", "taniyorum");
        SurveyQuestionOption unknownTwo = buildOption(politicianTwo, 2, "option_3", "Tan?m?yorum", "tanimiyorum");

        SurveyResponse completedResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112233",
                100,
                OffsetDateTime.now().minusMinutes(20)
        );

        SurveyAnswer firstAnswer = new SurveyAnswer();
        firstAnswer.setId(UUID.randomUUID());
        firstAnswer.setCompany(operation.getCompany());
        firstAnswer.setSurveyResponse(completedResponse);
        firstAnswer.setSurveyQuestion(politicianOne);
        firstAnswer.setAnswerType(QuestionType.SINGLE_CHOICE);
        firstAnswer.setAnswerText("Tanımıyorum.");
        firstAnswer.setRawInputText("Tanımıyorum.");
        firstAnswer.setAnswerJson("{\"normalizedText\":\"Tanımıyorum.\"}");
        firstAnswer.setValid(true);
        firstAnswer.setRetryCount(0);

        SurveyAnswer secondAnswer = new SurveyAnswer();
        secondAnswer.setId(UUID.randomUUID());
        secondAnswer.setCompany(operation.getCompany());
        secondAnswer.setSurveyResponse(completedResponse);
        secondAnswer.setSurveyQuestion(politicianTwo);
        secondAnswer.setAnswerType(QuestionType.SINGLE_CHOICE);
        secondAnswer.setAnswerText("Tanıyorum.");
        secondAnswer.setRawInputText("Tanıyorum.");
        secondAnswer.setAnswerJson("{\"normalizedText\":\"Tanıyorum.\"}");
        secondAnswer.setValid(true);
        secondAnswer.setRetryCount(0);

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(1L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(buildCallJob(operation, CallJobStatus.COMPLETED)));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(completedResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(politicianOne, politicianTwo));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(politicianOne.getId(), politicianTwo.getId())
        )).thenReturn(List.of(knowOne, unknownOne, knowTwo, unknownTwo));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(completedResponse.getId())))
                .thenReturn(List.of(firstAnswer, secondAnswer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionGroups()).hasSize(1);
        assertThat(response.questionGroups().getFirst().series())
                .extracting(item -> item.label() + ":" + item.data())
                .containsExactly(
                        "Tanıyorum:[0, 1]",
                        "Tan?m?yorum:[1, 0]"
                );
    }

    @Test
    void getOperationAnalytics_matchesChoiceAnswersAfterRemovingIntensityModifiers() {
        Operation operation = buildOperation(OperationStatus.COMPLETED, SurveyStatus.PUBLISHED);
        UUID companyId = operation.getCompany().getId();
        UUID operationId = operation.getId();

        SurveyQuestion favorabilityQuestion = buildQuestion(
                operation.getSurvey(),
                "q-like",
                1,
                QuestionType.SINGLE_CHOICE,
                "Adayi ne derece begeniyorsunuz?"
        );
        SurveyQuestionOption positiveOption = buildOption(favorabilityQuestion, 1, "option_1", "Beğeniyorum", "begeniyorum");
        SurveyQuestionOption negativeOption = buildOption(favorabilityQuestion, 2, "option_2", "Beğenmiyorum", "begenmiyorum");

        SurveyResponse completedResponse = buildSurveyResponse(
                operation,
                SurveyResponseStatus.COMPLETED,
                "905551112244",
                100,
                OffsetDateTime.now().minusMinutes(10)
        );

        SurveyAnswer answer = new SurveyAnswer();
        answer.setId(UUID.randomUUID());
        answer.setCompany(operation.getCompany());
        answer.setSurveyResponse(completedResponse);
        answer.setSurveyQuestion(favorabilityQuestion);
        answer.setAnswerType(QuestionType.SINGLE_CHOICE);
        answer.setAnswerText("Çok beğeniyorum.");
        answer.setRawInputText("Çok beğeniyorum.");
        answer.setAnswerJson("{\"normalizedText\":\"Çok beğeniyorum.\"}");
        answer.setValid(true);
        answer.setRetryCount(0);

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(Optional.of(operation));
        when(operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(operationId, companyId))
                .thenReturn(1L);
        when(callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId))
                .thenReturn(List.of(buildCallJob(operation, CallJobStatus.COMPLETED)));
        when(surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId))
                .thenReturn(List.of(completedResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId()))
                .thenReturn(List.of(favorabilityQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(favorabilityQuestion.getId())
        )).thenReturn(List.of(positiveOption, negativeOption));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(completedResponse.getId())))
                .thenReturn(List.of(answer));

        OperationAnalyticsResponseDto response = operationService.getOperationAnalytics(companyId, operationId);

        assertThat(response.questionSummaries()).hasSize(1);
        assertThat(response.questionSummaries().getFirst().breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .containsExactly("Beğeniyorum:1", "Beğenmiyorum:0");
    }

    private Operation buildOperation(OperationStatus status, SurveyStatus surveyStatus) {
        Company company = new Company();
        company.setId(UUID.randomUUID());

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setCompany(company);
        survey.setStatus(surveyStatus);
        survey.setName("Memnuniyet Anketi");

        Operation operation = new Operation();
        operation.setId(UUID.randomUUID());
        operation.setCompany(company);
        operation.setSurvey(survey);
        operation.setName("Nisan Baslangic Akisi");
        operation.setStatus(status);
        operation.setScheduledAt(OffsetDateTime.now().plusDays(1));
        return operation;
    }

    private OperationContact buildContact(Operation operation) {
        OperationContact contact = new OperationContact();
        contact.setId(UUID.randomUUID());
        contact.setCompany(operation.getCompany());
        contact.setOperation(operation);
        contact.setFirstName("Aylin");
        contact.setPhoneNumber("905551112233");
        contact.setStatus(OperationContactStatus.PENDING);
        contact.setRetryCount(0);
        contact.setMetadataJson("{}");
        return contact;
    }

    private CallJob buildCallJob(Operation operation, CallJobStatus status) {
        CallJob callJob = new CallJob();
        callJob.setId(UUID.randomUUID());
        callJob.setCompany(operation.getCompany());
        callJob.setOperation(operation);
        callJob.setStatus(status);
        return callJob;
    }

    private SurveyQuestion buildQuestion(Survey survey, String code, int order, QuestionType type, String title) {
        SurveyQuestion question = new SurveyQuestion();
        question.setId(UUID.randomUUID());
        question.setCompany(survey.getCompany());
        question.setSurvey(survey);
        question.setCode(code);
        question.setQuestionOrder(order);
        question.setQuestionType(type);
        question.setTitle(title);
        question.setBranchConditionJson("{}");
        question.setSettingsJson("{}");
        return question;
    }

    private SurveyQuestionOption buildOption(
            SurveyQuestion question,
            int order,
            String code,
            String label,
            String value
    ) {
        SurveyQuestionOption option = new SurveyQuestionOption();
        option.setId(UUID.randomUUID());
        option.setCompany(question.getCompany());
        option.setSurveyQuestion(question);
        option.setOptionOrder(order);
        option.setOptionCode(code);
        option.setLabel(label);
        option.setValue(value);
        option.setActive(true);
        return option;
    }

    private SurveyResponse buildSurveyResponse(
            Operation operation,
            SurveyResponseStatus status,
            String phoneNumber,
            int completionPercent,
            OffsetDateTime completedAt
    ) {
        OperationContact contact = buildContact(operation);
        CallJob callJob = buildCallJob(operation, CallJobStatus.COMPLETED);
        callJob.setOperationContact(contact);

        CallAttempt callAttempt = new CallAttempt();
        callAttempt.setId(UUID.randomUUID());
        callAttempt.setCompany(operation.getCompany());
        callAttempt.setCallJob(callJob);
        callAttempt.setOperation(operation);
        callAttempt.setOperationContact(contact);
        callAttempt.setAttemptNumber(1);
        callAttempt.setProvider(CallProvider.ELEVENLABS);
        callAttempt.setStatus(CallAttemptStatus.COMPLETED);
        callAttempt.setConnectedAt(completedAt.minusMinutes(6));
        callAttempt.setEndedAt(completedAt);
        callAttempt.setRawProviderPayload("{}");

        SurveyResponse response = new SurveyResponse();
        response.setId(UUID.randomUUID());
        response.setCompany(operation.getCompany());
        response.setSurvey(operation.getSurvey());
        response.setOperation(operation);
        response.setOperationContact(contact);
        response.setCallAttempt(callAttempt);
        response.setStatus(status);
        response.setRespondentPhone(phoneNumber);
        response.setCompletionPercent(BigDecimal.valueOf(completionPercent));
        response.setStartedAt(completedAt.minusMinutes(5));
        response.setCompletedAt(completedAt);
        response.setTranscriptJson("{}");
        return response;
    }

    private SurveyAnswer buildChoiceAnswer(
            SurveyResponse response,
            SurveyQuestion question,
            SurveyQuestionOption option
    ) {
        SurveyAnswer answer = new SurveyAnswer();
        answer.setId(UUID.randomUUID());
        answer.setCompany(response.getCompany());
        answer.setSurveyResponse(response);
        answer.setSurveyQuestion(question);
        answer.setAnswerType(QuestionType.SINGLE_CHOICE);
        answer.setSelectedOption(option);
        answer.setAnswerJson("{\"value\":\"YES\"}");
        answer.setValid(true);
        answer.setRetryCount(0);
        return answer;
    }

    private SurveyAnswer buildRatingAnswer(SurveyResponse response, SurveyQuestion question, int rating) {
        SurveyAnswer answer = new SurveyAnswer();
        answer.setId(UUID.randomUUID());
        answer.setCompany(response.getCompany());
        answer.setSurveyResponse(response);
        answer.setSurveyQuestion(question);
        answer.setAnswerType(QuestionType.RATING);
        answer.setAnswerNumber(BigDecimal.valueOf(rating));
        answer.setAnswerJson("{}");
        answer.setValid(true);
        answer.setRetryCount(0);
        return answer;
    }

    private SurveyAnswer buildOpenEndedAnswer(SurveyResponse response, SurveyQuestion question, String text) {
        SurveyAnswer answer = new SurveyAnswer();
        answer.setId(UUID.randomUUID());
        answer.setCompany(response.getCompany());
        answer.setSurveyResponse(response);
        answer.setSurveyQuestion(question);
        answer.setAnswerType(QuestionType.OPEN_ENDED);
        answer.setAnswerText(text);
        answer.setAnswerJson("{}");
        answer.setRawInputText(text);
        answer.setValid(true);
        answer.setRetryCount(0);
        return answer;
    }
}




