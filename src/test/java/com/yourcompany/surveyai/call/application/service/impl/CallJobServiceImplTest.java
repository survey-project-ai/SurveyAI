package com.yourcompany.surveyai.call.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.dto.request.UpdateCallJobSurveyResponseAnswerRequest;
import com.yourcompany.surveyai.call.application.dto.request.UpdateCallJobSurveyResponseRequest;
import com.yourcompany.surveyai.call.application.dto.response.CallJobDetailResponseDto;
import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.response.domain.entity.SurveyAnswer;
import com.yourcompany.surveyai.response.domain.entity.SurveyResponse;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import com.yourcompany.surveyai.response.repository.SurveyAnswerRepository;
import com.yourcompany.surveyai.response.repository.SurveyResponseRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionOptionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;

class CallJobServiceImplTest {

    private final CallJobRepository callJobRepository = mock(CallJobRepository.class);
    private final CallAttemptRepository callAttemptRepository = mock(CallAttemptRepository.class);
    private final OperationContactRepository operationContactRepository = mock(OperationContactRepository.class);
    private final OperationRepository operationRepository = mock(OperationRepository.class);
    private final SurveyResponseRepository surveyResponseRepository = mock(SurveyResponseRepository.class);
    private final SurveyAnswerRepository surveyAnswerRepository = mock(SurveyAnswerRepository.class);
    private final SurveyQuestionRepository surveyQuestionRepository = mock(SurveyQuestionRepository.class);
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository = mock(SurveyQuestionOptionRepository.class);
    private final CallJobDispatcher callJobDispatcher = mock(CallJobDispatcher.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CallJobServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CallJobServiceImpl(
                callJobRepository,
                callAttemptRepository,
                operationContactRepository,
                operationRepository,
                surveyResponseRepository,
                surveyAnswerRepository,
                surveyQuestionRepository,
                surveyQuestionOptionRepository,
                callJobDispatcher,
                objectMapper
        );

        when(callJobRepository.save(any(CallJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(operationContactRepository.save(any(OperationContact.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(surveyAnswerRepository.save(any(SurveyAnswer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(surveyResponseRepository.save(any(SurveyResponse.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void getOperationCallJobDetail_exposesAttemptHistoryAndLinkedResponse() {
        Fixture fixture = new Fixture();
        CallAttempt latestAttempt = fixture.buildAttempt(2, CallAttemptStatus.FAILED, "provider-2");
        latestAttempt.setFailureReason("Network timeout");
        latestAttempt.setTranscriptStorageKey("inline://attempt-2");
        CallAttempt firstAttempt = fixture.buildAttempt(1, CallAttemptStatus.COMPLETED, "provider-1");

        SurveyResponse surveyResponse = fixture.buildSurveyResponse(firstAttempt, SurveyResponseStatus.COMPLETED);
        surveyResponse.setAiSummaryText("Vatandas memnuniyetini belirtti.");
        surveyResponse.setTranscriptText("Merhaba, hizmetten memnunum.");
        SurveyAnswer validAnswer = fixture.buildAnswer(surveyResponse, true);
        SurveyAnswer invalidAnswer = fixture.buildAnswer(surveyResponse, false);

        mockJobContext(fixture);
        when(callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(fixture.callJob.getId()))
                .thenReturn(List.of(latestAttempt, firstAttempt));
        when(surveyResponseRepository.findAllByCallAttempt_IdInAndDeletedAtIsNull(List.of(latestAttempt.getId(), firstAttempt.getId())))
                .thenReturn(List.of(surveyResponse));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(surveyResponse.getId())))
                .thenReturn(List.of(validAnswer, invalidAnswer));

        CallJobDetailResponseDto detail = service.getOperationCallJobDetail(
                fixture.company.getId(),
                fixture.operation.getId(),
                fixture.callJob.getId()
        );

        assertThat(detail.retried()).isTrue();
        assertThat(detail.failed()).isTrue();
        assertThat(detail.retryable()).isTrue();
        assertThat(detail.partialResponseDataExists()).isTrue();
        assertThat(detail.failureReason()).isEqualTo("Network timeout");
        assertThat(detail.surveyResponse()).isNotNull();
        assertThat(detail.surveyResponse().usableResponse()).isTrue();
        assertThat(detail.attempts()).hasSize(2);
        assertThat(detail.attempts().get(0).latest()).isTrue();
        assertThat(detail.attempts().get(0).surveyResponse()).isNull();
        assertThat(detail.attempts().get(1).surveyResponse()).isNotNull();
        assertThat(detail.attempts().get(1).surveyResponse().usableResponse()).isTrue();
        assertThat(detail.attempts().get(1).surveyResponse().validAnswerCount()).isEqualTo(1);
        assertThat(detail.transcriptSummary()).isEqualTo("Vatandas memnuniyetini belirtti.");
        assertThat(detail.surveyResponse().answers()).hasSize(2);
        assertThat(detail.surveyResponse().answers().get(0).questionCode()).isEqualTo("Q1");
    }

    @Test
    void listOperationCallJobs_usesLatestUsableSurveyResponseAnswerCount() {
        Fixture fixture = new Fixture();
        CallAttempt latestAttempt = fixture.buildAttempt(2, CallAttemptStatus.FAILED, "provider-2");
        CallAttempt firstAttempt = fixture.buildAttempt(1, CallAttemptStatus.COMPLETED, "provider-1");

        SurveyResponse surveyResponse = fixture.buildSurveyResponse(firstAttempt, SurveyResponseStatus.COMPLETED);
        SurveyAnswer validAnswer = fixture.buildAnswer(surveyResponse, true);
        SurveyAnswer invalidAnswer = fixture.buildAnswer(surveyResponse, false);

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(fixture.operation.getId(), fixture.company.getId()))
                .thenReturn(Optional.of(fixture.operation));
        when(callJobRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(fixture.callJob)));
        when(callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(fixture.callJob.getId()))
                .thenReturn(List.of(latestAttempt, firstAttempt));
        when(surveyResponseRepository.findAllByCallAttempt_IdInAndDeletedAtIsNull(List.of(latestAttempt.getId(), firstAttempt.getId())))
                .thenReturn(List.of(surveyResponse));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(surveyResponse.getId())))
                .thenReturn(List.of(validAnswer, invalidAnswer));

        var page = service.listOperationCallJobs(
                fixture.company.getId(),
                fixture.operation.getId(),
                0,
                25,
                null,
                List.of(),
                "updatedAt",
                "desc"
        );

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).answerCount()).isEqualTo(1);
    }

    @Test
    void listOperationCallJobs_surfacesBusyReasonWhenNoAnswerExists() {
        Fixture fixture = new Fixture();
        fixture.callJob.setStatus(CallJobStatus.FAILED);
        CallAttempt busyAttempt = fixture.buildAttempt(1, CallAttemptStatus.BUSY, "provider-1");

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(fixture.operation.getId(), fixture.company.getId()))
                .thenReturn(Optional.of(fixture.operation));
        when(callJobRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(fixture.callJob)));
        when(callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(fixture.callJob.getId()))
                .thenReturn(List.of(busyAttempt));
        when(surveyResponseRepository.findAllByCallAttempt_IdInAndDeletedAtIsNull(List.of(busyAttempt.getId())))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of());

        var page = service.listOperationCallJobs(
                fixture.company.getId(),
                fixture.operation.getId(),
                0,
                25,
                null,
                List.of(),
                "updatedAt",
                "desc"
        );

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).answerCount()).isZero();
        assertThat(page.items().get(0).lastResultSummary()).isEqualTo("Hat mesgul.");
    }

    @Test
    void updateOperationCallJobSurveyResponse_persistsManualEditsAndRefreshesAnswerSummary() {
        Fixture fixture = new Fixture();
        CallAttempt completedAttempt = fixture.buildAttempt(1, CallAttemptStatus.COMPLETED, "provider-1");
        SurveyResponse surveyResponse = fixture.buildSurveyResponse(completedAttempt, SurveyResponseStatus.PARTIAL);
        surveyResponse.setCompletionPercent(BigDecimal.valueOf(50));

        SurveyAnswer commentAnswer = fixture.buildAnswer(surveyResponse, true);
        commentAnswer.setSurveyQuestion(fixture.commentQuestion);
        commentAnswer.setAnswerType(QuestionType.OPEN_ENDED);
        commentAnswer.setAnswerText("Eski yorum");
        commentAnswer.setRawInputText("Eski yorum");

        SurveyAnswer sentimentAnswer = fixture.buildAnswer(surveyResponse, false);
        sentimentAnswer.setSurveyQuestion(fixture.sentimentQuestion);
        sentimentAnswer.setAnswerType(QuestionType.SINGLE_CHOICE);
        sentimentAnswer.setAnswerText("Belirsiz");
        sentimentAnswer.setRawInputText("Belirsiz");
        sentimentAnswer.setInvalidReason("Secenek eslestirilemedi");

        List<SurveyAnswer> persistedAnswers = new ArrayList<>(List.of(commentAnswer, sentimentAnswer));

        mockJobContext(fixture);
        when(callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(fixture.callJob.getId()))
                .thenReturn(List.of(completedAttempt));
        when(surveyResponseRepository.findAllByCallAttempt_IdInAndDeletedAtIsNull(List.of(completedAttempt.getId())))
                .thenReturn(List.of(surveyResponse));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(surveyResponse.getId())))
                .thenReturn(List.copyOf(persistedAnswers));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdAndDeletedAtIsNull(surveyResponse.getId()))
                .thenAnswer(invocation -> List.copyOf(persistedAnswers));

        UpdateCallJobSurveyResponseAnswerRequest commentUpdate = new UpdateCallJobSurveyResponseAnswerRequest();
        commentUpdate.setQuestionId(fixture.commentQuestion.getId());
        commentUpdate.setAnswerText("Belediye hizmetleri hizlandi");

        UpdateCallJobSurveyResponseAnswerRequest sentimentUpdate = new UpdateCallJobSurveyResponseAnswerRequest();
        sentimentUpdate.setQuestionId(fixture.sentimentQuestion.getId());
        sentimentUpdate.setSelectedOptionId(fixture.positiveOption.getId());

        UpdateCallJobSurveyResponseRequest request = new UpdateCallJobSurveyResponseRequest();
        request.setAnswers(List.of(commentUpdate, sentimentUpdate));

        CallJobDetailResponseDto detail = service.updateOperationCallJobSurveyResponse(
                fixture.company.getId(),
                fixture.operation.getId(),
                fixture.callJob.getId(),
                request
        );

        assertThat(detail.surveyResponse()).isNotNull();
        assertThat(detail.surveyResponse().status()).isEqualTo(SurveyResponseStatus.COMPLETED);
        assertThat(detail.surveyResponse().validAnswerCount()).isEqualTo(2);
        assertThat(detail.surveyResponse().completionPercent()).isEqualByComparingTo("100.00");
        assertThat(detail.surveyResponse().answers())
                .extracting(answer -> answer.questionCode() + ":" + answer.displayValue())
                .contains("Q1:Belediye hizmetleri hizlandi", "Q2:Memnun");
        assertThat(detail.surveyResponse().answers())
                .allMatch(answer -> answer.manuallyEdited());
        assertThat(sentimentAnswer.getSelectedOption()).isEqualTo(fixture.positiveOption);
        assertThat(sentimentAnswer.isValid()).isTrue();
        assertThat(commentAnswer.getAnswerText()).isEqualTo("Belediye hizmetleri hizlandi");
    }

    @Test
    void updateOperationCallJobSurveyResponse_leavesUnchangedAnswersAndTheirThemesUntouched() {
        Fixture fixture = new Fixture();
        CallAttempt completedAttempt = fixture.buildAttempt(1, CallAttemptStatus.COMPLETED, "provider-1");
        SurveyResponse surveyResponse = fixture.buildSurveyResponse(completedAttempt, SurveyResponseStatus.PARTIAL);

        SurveyAnswer commentAnswer = fixture.buildAnswer(surveyResponse, true);
        commentAnswer.setSurveyQuestion(fixture.commentQuestion);
        commentAnswer.setAnswerText("Ulasim cok pahali");
        commentAnswer.setRawInputText("ulasim cok pahali");
        commentAnswer.setAnswerJson("{\"codedThemes\":[\"ulasim\"]}");

        SurveyAnswer sentimentAnswer = fixture.buildAnswer(surveyResponse, false);
        sentimentAnswer.setSurveyQuestion(fixture.sentimentQuestion);
        sentimentAnswer.setAnswerType(QuestionType.SINGLE_CHOICE);
        sentimentAnswer.setAnswerText("Belirsiz");

        mockManualEditContext(fixture, completedAttempt, surveyResponse, List.of(commentAnswer, sentimentAnswer));

        // The editor sends every question; only the sentiment answer was changed by the analyst.
        UpdateCallJobSurveyResponseAnswerRequest commentUnchanged = new UpdateCallJobSurveyResponseAnswerRequest();
        commentUnchanged.setQuestionId(fixture.commentQuestion.getId());
        commentUnchanged.setAnswerText("Ulasim cok pahali");
        commentUnchanged.setSelectedOptionIds(List.of());

        UpdateCallJobSurveyResponseAnswerRequest sentimentUpdate = new UpdateCallJobSurveyResponseAnswerRequest();
        sentimentUpdate.setQuestionId(fixture.sentimentQuestion.getId());
        sentimentUpdate.setSelectedOptionId(fixture.positiveOption.getId());
        sentimentUpdate.setSelectedOptionIds(List.of(fixture.positiveOption.getId()));

        UpdateCallJobSurveyResponseRequest request = new UpdateCallJobSurveyResponseRequest();
        request.setAnswers(List.of(commentUnchanged, sentimentUpdate));

        CallJobDetailResponseDto detail = service.updateOperationCallJobSurveyResponse(
                fixture.company.getId(),
                fixture.operation.getId(),
                fixture.callJob.getId(),
                request
        );

        assertThat(commentAnswer.getAnswerJson()).isEqualTo("{\"codedThemes\":[\"ulasim\"]}");
        assertThat(commentAnswer.getRawInputText()).isEqualTo("ulasim cok pahali");
        verify(surveyAnswerRepository, never()).save(commentAnswer);
        assertThat(sentimentAnswer.getSelectedOption()).isEqualTo(fixture.positiveOption);
        assertThat(detail.surveyResponse().answers())
                .extracting(answer -> answer.questionCode() + ":" + answer.manuallyEdited())
                .containsExactlyInAnyOrder("Q1:false", "Q2:true");
    }

    @Test
    void updateOperationCallJobSurveyResponse_keepsCodedThemesWhenEditedTextComesWithoutThemes() {
        Fixture fixture = new Fixture();
        CallAttempt completedAttempt = fixture.buildAttempt(1, CallAttemptStatus.COMPLETED, "provider-1");
        SurveyResponse surveyResponse = fixture.buildSurveyResponse(completedAttempt, SurveyResponseStatus.COMPLETED);

        SurveyAnswer commentAnswer = fixture.buildAnswer(surveyResponse, true);
        commentAnswer.setSurveyQuestion(fixture.commentQuestion);
        commentAnswer.setAnswerText("Ulasim cok pahli");
        commentAnswer.setAnswerJson("{\"codedThemes\":[\"ulasim\"]}");

        mockManualEditContext(fixture, completedAttempt, surveyResponse, List.of(commentAnswer));

        UpdateCallJobSurveyResponseAnswerRequest commentUpdate = new UpdateCallJobSurveyResponseAnswerRequest();
        commentUpdate.setQuestionId(fixture.commentQuestion.getId());
        commentUpdate.setAnswerText("Ulasim cok pahali");

        UpdateCallJobSurveyResponseRequest request = new UpdateCallJobSurveyResponseRequest();
        request.setAnswers(List.of(commentUpdate));

        service.updateOperationCallJobSurveyResponse(
                fixture.company.getId(),
                fixture.operation.getId(),
                fixture.callJob.getId(),
                request
        );

        assertThat(commentAnswer.getAnswerText()).isEqualTo("Ulasim cok pahali");
        assertThat(commentAnswer.getAnswerJson()).contains("\"manualEdit\":true", "\"codedThemes\":[\"ulasim\"]");
    }

    @Test
    void retryOperationCallJob_dispatchesNewAttemptForEligibleFailedJob() {
        Fixture fixture = new Fixture();
        CallAttempt failedAttempt = fixture.buildAttempt(1, CallAttemptStatus.FAILED, "provider-1");
        failedAttempt.setFailureReason("Provider rejected call");

        List<CallAttempt> attempts = new ArrayList<>();
        attempts.add(failedAttempt);

        mockJobContext(fixture);
        when(callAttemptRepository.findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(fixture.callJob.getId()))
                .thenReturn(Optional.of(failedAttempt));
        when(callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(fixture.callJob.getId()))
                .thenAnswer(invocation -> List.copyOf(attempts));
        when(surveyResponseRepository.findAllByCallAttempt_IdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of());

        doAnswer(invocation -> {
            fixture.callJob.setAttemptCount(2);
            fixture.callJob.setStatus(CallJobStatus.QUEUED);
            fixture.callJob.setLastErrorCode(null);
            fixture.callJob.setLastErrorMessage(null);
            fixture.contact.setStatus(OperationContactStatus.CALLING);

            CallAttempt retriedAttempt = fixture.buildAttempt(2, CallAttemptStatus.INITIATED, "provider-2");
            attempts.clear();
            attempts.add(retriedAttempt);
            attempts.add(failedAttempt);
            return null;
        }).when(callJobDispatcher).dispatchPreparedJobs(any());

        CallJobDetailResponseDto detail = service.retryOperationCallJob(
                fixture.company.getId(),
                fixture.operation.getId(),
                fixture.callJob.getId()
        );

        verify(callJobDispatcher).dispatchPreparedJobs(any());
        assertThat(fixture.contact.getRetryCount()).isEqualTo(1);
        assertThat(detail.retryable()).isFalse();
        assertThat(detail.rawStatus()).isEqualTo(CallJobStatus.QUEUED);
        assertThat(detail.attemptCount()).isEqualTo(2);
        assertThat(detail.retried()).isTrue();
        assertThat(detail.attempts()).hasSize(2);
        assertThat(detail.attempts().get(0).attemptNumber()).isEqualTo(2);
    }

    @Test
    void retryOperationCallJob_allowsCompletedStateForTesting() {
        Fixture fixture = new Fixture();
        fixture.callJob.setStatus(CallJobStatus.COMPLETED);
        fixture.callJob.setAttemptCount(1);

        List<CallAttempt> attempts = new ArrayList<>();
        CallAttempt completedAttempt = fixture.buildAttempt(1, CallAttemptStatus.COMPLETED, "provider-1");
        attempts.add(completedAttempt);

        mockJobContext(fixture);
        when(callAttemptRepository.findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(fixture.callJob.getId()))
                .thenReturn(Optional.of(completedAttempt));
        when(callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(fixture.callJob.getId()))
                .thenAnswer(invocation -> List.copyOf(attempts));
        when(surveyResponseRepository.findAllByCallAttempt_IdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of());

        doAnswer(invocation -> {
            fixture.callJob.setAttemptCount(2);
            fixture.callJob.setStatus(CallJobStatus.QUEUED);
            fixture.contact.setStatus(OperationContactStatus.CALLING);
            CallAttempt retriedAttempt = fixture.buildAttempt(2, CallAttemptStatus.INITIATED, "provider-2");
            attempts.clear();
            attempts.add(retriedAttempt);
            attempts.add(completedAttempt);
            return null;
        }).when(callJobDispatcher).dispatchPreparedJobs(any());

        CallJobDetailResponseDto detail = service.retryOperationCallJob(
                fixture.company.getId(),
                fixture.operation.getId(),
                fixture.callJob.getId()
        );

        verify(callJobDispatcher).dispatchPreparedJobs(any());
        assertThat(detail.rawStatus()).isEqualTo(CallJobStatus.QUEUED);
        assertThat(detail.retryable()).isFalse();
        assertThat(detail.attemptCount()).isEqualTo(2);
    }

    @Test
    void retryOperationCallJob_rejectsActiveAttempt() {
        Fixture fixture = new Fixture();
        fixture.callJob.setStatus(CallJobStatus.IN_PROGRESS);

        mockJobContext(fixture);
        when(callAttemptRepository.findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(fixture.callJob.getId()))
                .thenReturn(Optional.of(fixture.buildAttempt(1, CallAttemptStatus.IN_PROGRESS, "provider-1")));

        assertThatThrownBy(() -> service.retryOperationCallJob(
                fixture.company.getId(),
                fixture.operation.getId(),
                fixture.callJob.getId()
        ))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Call job already has an active execution attempt");

        verify(callJobDispatcher, never()).dispatchPreparedJobs(any());
    }

    @Test
    void redialOperationCallJob_createsFreshJobAndDispatchesIt() {
        Fixture fixture = new Fixture();
        fixture.callJob.setStatus(CallJobStatus.COMPLETED);
        fixture.callJob.setAttemptCount(1);
        fixture.contact.setStatus(OperationContactStatus.COMPLETED);

        CallAttempt completedAttempt = fixture.buildAttempt(1, CallAttemptStatus.COMPLETED, "provider-1");

        mockJobContext(fixture);
        when(callAttemptRepository.findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(fixture.callJob.getId()))
                .thenReturn(Optional.of(completedAttempt));
        when(callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(fixture.callJob.getId()))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(surveyResponseRepository.findAllByCallAttempt_IdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of());

        final CallJob[] savedRedialJobRef = new CallJob[1];
        doAnswer(invocation -> {
            CallJob savedJob = invocation.getArgument(0);
            if (savedJob.getId() == null) {
                savedJob.setId(UUID.randomUUID());
            }
            savedRedialJobRef[0] = savedJob;
            return savedJob;
        }).when(callJobRepository).save(argThat(job -> job != fixture.callJob));
        when(callJobRepository.findByIdAndOperation_IdAndCompany_IdAndDeletedAtIsNull(
                argThat(id -> savedRedialJobRef[0] != null && savedRedialJobRef[0].getId().equals(id)),
                org.mockito.ArgumentMatchers.eq(fixture.operation.getId()),
                org.mockito.ArgumentMatchers.eq(fixture.company.getId())
        )).thenAnswer(invocation -> Optional.of(savedRedialJobRef[0]));

        doAnswer(invocation -> {
            CallJob dispatchedJob = invocation.<List<CallJob>>getArgument(0).get(0);
            dispatchedJob.setAttemptCount(1);
            dispatchedJob.setStatus(CallJobStatus.QUEUED);
            fixture.contact.setStatus(OperationContactStatus.CALLING);
            return null;
        }).when(callJobDispatcher).dispatchPreparedJobs(any());

        CallJobDetailResponseDto detail = service.redialOperationCallJob(
                fixture.company.getId(),
                fixture.operation.getId(),
                fixture.callJob.getId()
        );

        verify(callJobDispatcher).dispatchPreparedJobs(argThat(jobs ->
                jobs.size() == 1
                        && savedRedialJobRef[0] != null
                        && jobs.get(0).getId().equals(savedRedialJobRef[0].getId())
        ));
        assertThat(savedRedialJobRef[0]).isNotNull();
        assertThat(savedRedialJobRef[0].getOperationContact().getId()).isEqualTo(fixture.contact.getId());
        assertThat(savedRedialJobRef[0].getIdempotencyKey()).contains(":redial:");
        assertThat(detail.id()).isEqualTo(savedRedialJobRef[0].getId());
        assertThat(detail.rawStatus()).isEqualTo(CallJobStatus.QUEUED);
        assertThat(detail.redialable()).isTrue();
        assertThat(fixture.contact.getRetryCount()).isEqualTo(1);
    }

    @Test
    void redialOperationCallJob_allowsNonTerminalStatusWhenNoActiveAttemptExists() {
        Fixture fixture = new Fixture();
        fixture.callJob.setStatus(CallJobStatus.QUEUED);
        fixture.callJob.setAttemptCount(1);
        fixture.contact.setStatus(OperationContactStatus.RETRY);

        CallAttempt queuedAttempt = fixture.buildAttempt(1, CallAttemptStatus.FAILED, "provider-1");

        mockJobContext(fixture);
        when(callAttemptRepository.findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(fixture.callJob.getId()))
                .thenReturn(Optional.of(queuedAttempt));
        when(callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(fixture.callJob.getId()))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(surveyResponseRepository.findAllByCallAttempt_IdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of());

        final CallJob[] savedRedialJobRef = new CallJob[1];
        doAnswer(invocation -> {
            CallJob savedJob = invocation.getArgument(0);
            if (savedJob.getId() == null) {
                savedJob.setId(UUID.randomUUID());
            }
            savedRedialJobRef[0] = savedJob;
            return savedJob;
        }).when(callJobRepository).save(argThat(job -> job != fixture.callJob));
        when(callJobRepository.findByIdAndOperation_IdAndCompany_IdAndDeletedAtIsNull(
                argThat(id -> savedRedialJobRef[0] != null && savedRedialJobRef[0].getId().equals(id)),
                org.mockito.ArgumentMatchers.eq(fixture.operation.getId()),
                org.mockito.ArgumentMatchers.eq(fixture.company.getId())
        )).thenAnswer(invocation -> Optional.of(savedRedialJobRef[0]));

        doAnswer(invocation -> {
            CallJob dispatchedJob = invocation.<List<CallJob>>getArgument(0).get(0);
            dispatchedJob.setAttemptCount(1);
            dispatchedJob.setStatus(CallJobStatus.QUEUED);
            fixture.contact.setStatus(OperationContactStatus.CALLING);
            return null;
        }).when(callJobDispatcher).dispatchPreparedJobs(any());

        CallJobDetailResponseDto detail = service.redialOperationCallJob(
                fixture.company.getId(),
                fixture.operation.getId(),
                fixture.callJob.getId()
        );

        verify(callJobDispatcher).dispatchPreparedJobs(any());
        assertThat(detail.id()).isEqualTo(savedRedialJobRef[0].getId());
        assertThat(detail.rawStatus()).isEqualTo(CallJobStatus.QUEUED);
    }

    private void mockManualEditContext(
            Fixture fixture,
            CallAttempt attempt,
            SurveyResponse surveyResponse,
            List<SurveyAnswer> answers
    ) {
        mockJobContext(fixture);
        when(callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(fixture.callJob.getId()))
                .thenReturn(List.of(attempt));
        when(surveyResponseRepository.findAllByCallAttempt_IdInAndDeletedAtIsNull(List.of(attempt.getId())))
                .thenReturn(List.of(surveyResponse));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(List.of(surveyResponse.getId())))
                .thenReturn(List.copyOf(answers));
        when(surveyAnswerRepository.findAllBySurveyResponse_IdAndDeletedAtIsNull(surveyResponse.getId()))
                .thenAnswer(invocation -> List.copyOf(answers));
    }

    private void mockJobContext(Fixture fixture) {
        CallJob callJob = fixture.callJob;
        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(callJob.getOperation().getId(), callJob.getCompany().getId()))
                .thenReturn(Optional.of(callJob.getOperation()));
        when(callJobRepository.findByIdAndOperation_IdAndCompany_IdAndDeletedAtIsNull(
                callJob.getId(),
                callJob.getOperation().getId(),
                callJob.getCompany().getId()
        )).thenReturn(Optional.of(callJob));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.commentQuestion, fixture.sentimentQuestion));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                List.of(fixture.commentQuestion.getId(), fixture.sentimentQuestion.getId())
        )).thenReturn(List.of(fixture.positiveOption, fixture.negativeOption));
    }

    private static class Fixture {
        private final Company company = new Company();
        private final Survey survey = new Survey();
        private final Operation operation = new Operation();
        private final OperationContact contact = new OperationContact();
        private final CallJob callJob = new CallJob();
        private final SurveyQuestion commentQuestion = new SurveyQuestion();
        private final SurveyQuestion sentimentQuestion = new SurveyQuestion();
        private final SurveyQuestionOption positiveOption = new SurveyQuestionOption();
        private final SurveyQuestionOption negativeOption = new SurveyQuestionOption();

        private Fixture() {
            company.setId(UUID.randomUUID());

            survey.setId(UUID.randomUUID());
            survey.setCompany(company);
            survey.setName("Vatandas Memnuniyeti");

            commentQuestion.setId(UUID.randomUUID());
            commentQuestion.setCompany(company);
            commentQuestion.setSurvey(survey);
            commentQuestion.setCode("Q1");
            commentQuestion.setQuestionOrder(1);
            commentQuestion.setQuestionType(QuestionType.OPEN_ENDED);
            commentQuestion.setTitle("Gorusunuz nedir?");
            commentQuestion.setRequired(true);
            commentQuestion.setBranchConditionJson("{}");
            commentQuestion.setSettingsJson("{}");

            sentimentQuestion.setId(UUID.randomUUID());
            sentimentQuestion.setCompany(company);
            sentimentQuestion.setSurvey(survey);
            sentimentQuestion.setCode("Q2");
            sentimentQuestion.setQuestionOrder(2);
            sentimentQuestion.setQuestionType(QuestionType.SINGLE_CHOICE);
            sentimentQuestion.setTitle("Genel memnuniyetiniz");
            sentimentQuestion.setRequired(true);
            sentimentQuestion.setBranchConditionJson("{}");
            sentimentQuestion.setSettingsJson("{}");

            positiveOption.setId(UUID.randomUUID());
            positiveOption.setCompany(company);
            positiveOption.setSurveyQuestion(sentimentQuestion);
            positiveOption.setOptionOrder(1);
            positiveOption.setOptionCode("POSITIVE");
            positiveOption.setLabel("Memnun");
            positiveOption.setValue("memnun");
            positiveOption.setActive(true);

            negativeOption.setId(UUID.randomUUID());
            negativeOption.setCompany(company);
            negativeOption.setSurveyQuestion(sentimentQuestion);
            negativeOption.setOptionOrder(2);
            negativeOption.setOptionCode("NEGATIVE");
            negativeOption.setLabel("Memnun degil");
            negativeOption.setValue("memnun_degil");
            negativeOption.setActive(true);

            operation.setId(UUID.randomUUID());
            operation.setCompany(company);
            operation.setSurvey(survey);
            operation.setName("Mart Operasyonu");

            contact.setId(UUID.randomUUID());
            contact.setCompany(company);
            contact.setOperation(operation);
            contact.setFirstName("Ayse");
            contact.setLastName("Demir");
            contact.setPhoneNumber("905551112233");
            contact.setRetryCount(0);
            contact.setStatus(OperationContactStatus.FAILED);
            contact.setMetadataJson("{}");

            callJob.setId(UUID.randomUUID());
            callJob.setCompany(company);
            callJob.setOperation(operation);
            callJob.setOperationContact(contact);
            callJob.setStatus(CallJobStatus.FAILED);
            callJob.setAttemptCount(1);
            callJob.setMaxAttempts(3);
            callJob.setScheduledFor(OffsetDateTime.now().minusHours(2));
            callJob.setAvailableAt(OffsetDateTime.now().minusHours(2));
            callJob.setIdempotencyKey(operation.getId() + ":" + contact.getId());
            callJob.setLastErrorCode("TEMP_ERROR");
            callJob.setLastErrorMessage("Last attempt failed");
        }

        private CallAttempt buildAttempt(int attemptNumber, CallAttemptStatus status, String providerCallId) {
            CallAttempt attempt = new CallAttempt();
            attempt.setId(UUID.randomUUID());
            attempt.setCompany(company);
            attempt.setCallJob(callJob);
            attempt.setOperation(operation);
            attempt.setOperationContact(contact);
            attempt.setAttemptNumber(attemptNumber);
            attempt.setProvider(CallProvider.MOCK);
            attempt.setProviderCallId(providerCallId);
            attempt.setStatus(status);
            attempt.setDialedAt(OffsetDateTime.now().minusMinutes(10L * attemptNumber));
            attempt.setRawProviderPayload("{}");
            return attempt;
        }

        private SurveyResponse buildSurveyResponse(CallAttempt attempt, SurveyResponseStatus status) {
            SurveyResponse response = new SurveyResponse();
            response.setId(UUID.randomUUID());
            response.setCompany(company);
            response.setSurvey(survey);
            response.setOperation(operation);
            response.setOperationContact(contact);
            response.setCallAttempt(attempt);
            response.setStatus(status);
            response.setCompletionPercent(BigDecimal.valueOf(100));
            response.setRespondentPhone(contact.getPhoneNumber());
            response.setStartedAt(OffsetDateTime.now().minusMinutes(9));
            response.setCompletedAt(OffsetDateTime.now().minusMinutes(8));
            response.setTranscriptJson("{}");
            return response;
        }

        private SurveyAnswer buildAnswer(SurveyResponse response, boolean valid) {
            SurveyAnswer answer = new SurveyAnswer();
            answer.setId(UUID.randomUUID());
            answer.setCompany(company);
            answer.setSurveyResponse(response);
            answer.setSurveyQuestion(commentQuestion);
            answer.setAnswerType(QuestionType.OPEN_ENDED);
            answer.setValid(valid);
            answer.setRetryCount(0);
            answer.setAnswerJson("{}");
            return answer;
        }
    }
}
