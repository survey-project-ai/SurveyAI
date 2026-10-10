package com.yourcompany.surveyai.response.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.model.ProviderCorrelationMetadata;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookEvent;
import com.yourcompany.surveyai.call.application.service.ProviderExecutionObservationService;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.response.domain.entity.SurveyAnswer;
import com.yourcompany.surveyai.response.domain.entity.SurveyResponse;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import com.yourcompany.surveyai.response.application.model.IngestedSurveyAnswer;
import com.yourcompany.surveyai.response.application.model.IngestedSurveyResult;
import com.yourcompany.surveyai.response.application.provider.ProviderSurveyResultMapper;
import com.yourcompany.surveyai.response.infrastructure.provider.elevenlabs.ElevenLabsSurveyResultMapper;
import com.yourcompany.surveyai.response.infrastructure.provider.mock.MockSurveyResultMapper;
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

class SurveyResponseIngestionServiceImplTest {

    private final SurveyResponseRepository surveyResponseRepository = mock(SurveyResponseRepository.class);
    private final SurveyAnswerRepository surveyAnswerRepository = mock(SurveyAnswerRepository.class);
    private final SurveyQuestionRepository surveyQuestionRepository = mock(SurveyQuestionRepository.class);
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository = mock(SurveyQuestionOptionRepository.class);
    private final ProviderExecutionObservationService providerExecutionObservationService = mock(ProviderExecutionObservationService.class);

    private final List<SurveyResponse> savedResponses = new ArrayList<>();
    private final List<SurveyAnswer> savedAnswers = new ArrayList<>();

    private SurveyResponseIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        savedResponses.clear();
        savedAnswers.clear();
        service = new SurveyResponseIngestionServiceImpl(
                new com.yourcompany.surveyai.response.application.provider.ProviderSurveyResultMapperRegistry(List.of(
                        new ElevenLabsSurveyResultMapper(new ObjectMapper()),
                        new MockSurveyResultMapper(new ObjectMapper())
                )),
                surveyResponseRepository,
                surveyAnswerRepository,
                surveyQuestionRepository,
                surveyQuestionOptionRepository,
                new ObjectMapper(),
                providerExecutionObservationService
        );

        when(surveyResponseRepository.save(any(SurveyResponse.class))).thenAnswer(invocation -> {
            SurveyResponse response = invocation.getArgument(0);
            if (response.getId() == null) {
                response.setId(UUID.randomUUID());
            }
            savedResponses.add(response);
            return response;
        });
        when(surveyAnswerRepository.save(any(SurveyAnswer.class))).thenAnswer(invocation -> {
            SurveyAnswer answer = invocation.getArgument(0);
            if (answer.getId() == null) {
                answer.setId(UUID.randomUUID());
            }
            savedAnswers.add(answer);
            return answer;
        });
    }

    @Test
    void ingest_createsSurveyResponseAndAnswersFromElevenLabsStructuredResults() {
        TestFixture fixture = buildFixture();
        when(surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(fixture.callAttempt().getId())).thenReturn(Optional.empty());
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey().getId()))
                .thenReturn(List.of(fixture.choiceQuestion(), fixture.ratingQuestion()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.choiceQuestion().getId()))
                .thenReturn(List.of(fixture.yesOption(), fixture.noOption()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.ratingQuestion().getId()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());

        service.ingest(fixture.callAttempt(), fixture.completedWebhook());

        assertThat(savedResponses).isNotEmpty();
        SurveyResponse response = savedResponses.getLast();
        assertThat(response.getStatus()).isEqualTo(SurveyResponseStatus.COMPLETED);
        assertThat(response.getTranscriptText()).contains("agent: Hello");
        assertThat(savedAnswers).hasSize(2);
        assertThat(savedAnswers)
                .anySatisfy(answer -> {
                    assertThat(answer.getSurveyQuestion().getCode()).isEqualTo("consent");
                    assertThat(answer.getSelectedOption()).isEqualTo(fixture.yesOption());
                })
                .anySatisfy(answer -> {
                    assertThat(answer.getSurveyQuestion().getCode()).isEqualTo("nps");
                    assertThat(answer.getAnswerNumber()).isEqualByComparingTo(BigDecimal.valueOf(8));
                });
    }

    @Test
    void ingest_stripsVoiceDirectionTagsFromTranscriptText() {
        TestFixture fixture = buildFixture();
        ProviderWebhookEvent webhookWithDirections = new ProviderWebhookEvent(
                CallProvider.ELEVENLABS,
                "conv_123",
                "op:contact",
                "post_call_transcription",
                CallJobStatus.COMPLETED,
                CallAttemptStatus.COMPLETED,
                OffsetDateTime.now(),
                45,
                new ProviderCorrelationMetadata(
                        fixture.operation().getId(),
                        fixture.contact().getId(),
                        fixture.callJob().getId(),
                        fixture.callAttempt().getId()
                ),
                null,
                null,
                "inline://elevenlabs/conversations/conv_123",
                "agent: [slow] Hello\nuser: (sad) Hi",
                """
                {
                  "type": "post_call_transcription",
                  "data": {
                    "conversation_id": "conv_123",
                    "status": "done",
                    "transcript": [
                      {"role": "agent", "message": "[slow] Hello"},
                      {"role": "user", "message": "(sad) Hi"}
                    ],
                    "analysis": {
                      "transcript_summary": "Summary",
                      "data_collection_results": {
                        "consent": {"value": "yes"},
                        "nps": {"value": "8", "number": 8}
                      }
                    }
                  }
                }
                """
        );
        when(surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(fixture.callAttempt().getId())).thenReturn(Optional.empty());
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey().getId()))
                .thenReturn(List.of(fixture.choiceQuestion(), fixture.ratingQuestion()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.choiceQuestion().getId()))
                .thenReturn(List.of(fixture.yesOption(), fixture.noOption()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.ratingQuestion().getId()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());

        service.ingest(fixture.callAttempt(), webhookWithDirections);

        SurveyResponse response = savedResponses.getLast();
        assertThat(response.getTranscriptText()).isEqualTo("agent: Hello\nuser: Hi");
    }

    @Test
    void ingest_updatesExistingResponseInsteadOfCreatingDuplicateRecords() {
        TestFixture fixture = buildFixture();
        SurveyResponse existingResponse = new SurveyResponse();
        existingResponse.setId(UUID.randomUUID());
        existingResponse.setCompany(fixture.company());
        existingResponse.setSurvey(fixture.survey());
        existingResponse.setOperation(fixture.operation());
        existingResponse.setOperationContact(fixture.contact());
        existingResponse.setCallAttempt(fixture.callAttempt());
        existingResponse.setStatus(SurveyResponseStatus.PARTIAL);
        existingResponse.setCompletionPercent(BigDecimal.ZERO);
        existingResponse.setRespondentPhone(fixture.contact().getPhoneNumber());
        existingResponse.setStartedAt(OffsetDateTime.now());
        existingResponse.setTranscriptJson("{}");

        SurveyAnswer existingAnswer = new SurveyAnswer();
        existingAnswer.setId(UUID.randomUUID());
        existingAnswer.setCompany(fixture.company());
        existingAnswer.setSurveyResponse(existingResponse);
        existingAnswer.setSurveyQuestion(fixture.choiceQuestion());
        existingAnswer.setAnswerType(QuestionType.SINGLE_CHOICE);
        existingAnswer.setAnswerJson("{}");
        existingAnswer.setRetryCount(0);
        existingAnswer.setValid(true);

        when(surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(fixture.callAttempt().getId())).thenReturn(Optional.of(existingResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey().getId()))
                .thenReturn(List.of(fixture.choiceQuestion(), fixture.ratingQuestion()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.choiceQuestion().getId()))
                .thenReturn(List.of(fixture.yesOption(), fixture.noOption()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.ratingQuestion().getId()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(existingResponse.getId(), fixture.choiceQuestion().getId()))
                .thenReturn(Optional.of(existingAnswer));
        when(surveyAnswerRepository.findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(existingResponse.getId(), fixture.ratingQuestion().getId()))
                .thenReturn(Optional.empty());

        service.ingest(fixture.callAttempt(), fixture.completedWebhook());

        assertThat(savedResponses).isNotEmpty();
        assertThat(savedResponses.getLast().getId()).isEqualTo(existingResponse.getId());
        assertThat(savedAnswers).hasSize(1);
        assertThat(savedAnswers.getFirst().getSurveyQuestion().getCode()).isEqualTo("nps");
    }

    @Test
    void ingest_keepsTheAbandonedOutcomeOfADeclinedConsentAndOnlyAddsTheTranscript() {
        TestFixture fixture = buildFixture();
        SurveyResponse existingResponse = new SurveyResponse();
        existingResponse.setId(UUID.randomUUID());
        existingResponse.setCompany(fixture.company());
        existingResponse.setSurvey(fixture.survey());
        existingResponse.setOperation(fixture.operation());
        existingResponse.setOperationContact(fixture.contact());
        existingResponse.setCallAttempt(fixture.callAttempt());
        existingResponse.setStatus(SurveyResponseStatus.ABANDONED);
        existingResponse.setCompletionPercent(BigDecimal.ZERO);
        existingResponse.setRespondentPhone(fixture.contact().getPhoneNumber());
        existingResponse.setStartedAt(OffsetDateTime.now());
        existingResponse.setTranscriptJson("""
                {
                  "openingConsentState": "DECLINED",
                  "openingConsentPromptDelivered": true
                }
                """);

        when(surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(fixture.callAttempt().getId())).thenReturn(Optional.of(existingResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey().getId()))
                .thenReturn(List.of(fixture.choiceQuestion(), fixture.ratingQuestion()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.choiceQuestion().getId()))
                .thenReturn(List.of(fixture.yesOption(), fixture.noOption()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.ratingQuestion().getId()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());

        service.ingest(fixture.callAttempt(), fixture.completedWebhook());

        SurveyResponse savedResponse = savedResponses.getLast();
        assertThat(savedResponse.getStatus()).isEqualTo(SurveyResponseStatus.ABANDONED);
        assertThat(savedResponse.getCompletionPercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(savedResponse.getTranscriptText()).contains("agent: Hello");
        assertThat(savedResponse.getAiSummaryText()).isNotBlank();
        assertThat(savedResponse.getTranscriptJson()).contains("\"openingConsentState\":\"DECLINED\"");
        assertThat(savedResponse.getTranscriptJson()).contains("\"providerCallId\":\"conv_123\"");
        assertThat(savedAnswers).isEmpty();
    }

    @Test
    void ingest_keepsTheStatusAndCompletionOfAnInterviewCompletedDuringTheCall() {
        TestFixture fixture = buildFixture();
        SurveyResponse existingResponse = new SurveyResponse();
        existingResponse.setId(UUID.randomUUID());
        existingResponse.setCompany(fixture.company());
        existingResponse.setSurvey(fixture.survey());
        existingResponse.setOperation(fixture.operation());
        existingResponse.setOperationContact(fixture.contact());
        existingResponse.setCallAttempt(fixture.callAttempt());
        existingResponse.setStatus(SurveyResponseStatus.COMPLETED);
        // Branching skipped the second question, so the live interview completed at 100%.
        existingResponse.setCompletionPercent(BigDecimal.valueOf(100));
        OffsetDateTime completedAt = OffsetDateTime.now().minusMinutes(1);
        existingResponse.setCompletedAt(completedAt);
        existingResponse.setStartedAt(OffsetDateTime.now().minusMinutes(3));
        existingResponse.setTranscriptJson("{}");

        when(surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(fixture.callAttempt().getId())).thenReturn(Optional.of(existingResponse));

        service.ingest(fixture.callAttempt(), fixture.completedWebhook());

        SurveyResponse savedResponse = savedResponses.getLast();
        assertThat(savedResponse.getStatus()).isEqualTo(SurveyResponseStatus.COMPLETED);
        assertThat(savedResponse.getCompletionPercent()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(savedResponse.getCompletedAt()).isEqualTo(completedAt);
        assertThat(savedResponse.getTranscriptText()).contains("agent: Hello");
        assertThat(savedAnswers).isEmpty();
    }

    @Test
    void ingest_ignoresDataCollectionItemsWithoutAValue() {
        TestFixture fixture = buildFixture();
        when(surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(fixture.callAttempt().getId())).thenReturn(Optional.empty());
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey().getId()))
                .thenReturn(List.of(fixture.choiceQuestion(), fixture.ratingQuestion()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.choiceQuestion().getId()))
                .thenReturn(List.of(fixture.yesOption(), fixture.noOption()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.ratingQuestion().getId()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());
        when(surveyAnswerRepository.findAllBySurveyResponse_IdAndDeletedAtIsNull(any())).thenAnswer(invocation -> List.copyOf(savedAnswers));

        service.ingest(fixture.callAttempt(), elevenLabsWebhook(fixture, """
                {
                  "survey_question_1_response": {"data_collection_id": "survey_question_1_response", "value": "yes"},
                  "survey_question_2_response": {"data_collection_id": "survey_question_2_response", "value": null, "rationale": "Not asked"}
                }
                """));

        assertThat(savedAnswers).hasSize(1);
        assertThat(savedAnswers.getFirst().getSurveyQuestion().getQuestionOrder()).isEqualTo(1);
        SurveyResponse savedResponse = savedResponses.getLast();
        assertThat(savedResponse.getStatus()).isEqualTo(SurveyResponseStatus.PARTIAL);
        assertThat(savedResponse.getTranscriptJson()).doesNotContain("unmappedFields\":[\"survey_question_2_response");
    }

    private ProviderWebhookEvent elevenLabsWebhook(TestFixture fixture, String dataCollectionResults) {
        return new ProviderWebhookEvent(
                CallProvider.ELEVENLABS,
                "conv_123",
                "op:contact",
                "post_call_transcription",
                CallJobStatus.COMPLETED,
                CallAttemptStatus.COMPLETED,
                OffsetDateTime.now(),
                45,
                new ProviderCorrelationMetadata(
                        fixture.operation().getId(),
                        fixture.contact().getId(),
                        fixture.callJob().getId(),
                        fixture.callAttempt().getId()
                ),
                null,
                null,
                "inline://elevenlabs/conversations/conv_123",
                "agent: Hello\nuser: Hi",
                """
                {
                  "type": "post_call_transcription",
                  "data": {
                    "conversation_id": "conv_123",
                    "status": "done",
                    "analysis": {
                      "transcript_summary": "Summary",
                      "data_collection_results": %s
                    }
                  }
                }
                """.formatted(dataCollectionResults)
        );
    }

    @Test
    void ingest_preservesExistingValidLiveToolAnswerWhenProviderPayloadDisagrees() {
        TestFixture fixture = buildFixture();
        SurveyResponse existingResponse = new SurveyResponse();
        existingResponse.setId(UUID.randomUUID());
        existingResponse.setCompany(fixture.company());
        existingResponse.setSurvey(fixture.survey());
        existingResponse.setOperation(fixture.operation());
        existingResponse.setOperationContact(fixture.contact());
        existingResponse.setCallAttempt(fixture.callAttempt());
        existingResponse.setStatus(SurveyResponseStatus.PARTIAL);
        existingResponse.setCompletionPercent(BigDecimal.ZERO);
        existingResponse.setRespondentPhone(fixture.contact().getPhoneNumber());
        existingResponse.setStartedAt(OffsetDateTime.now());
        existingResponse.setTranscriptJson("{}");

        SurveyAnswer existingAnswer = new SurveyAnswer();
        existingAnswer.setId(UUID.randomUUID());
        existingAnswer.setCompany(fixture.company());
        existingAnswer.setSurveyResponse(existingResponse);
        existingAnswer.setSurveyQuestion(fixture.choiceQuestion());
        existingAnswer.setAnswerType(QuestionType.SINGLE_CHOICE);
        existingAnswer.setSelectedOption(fixture.yesOption());
        existingAnswer.setAnswerText("Yes");
        existingAnswer.setRawInputText("Yes");
        existingAnswer.setRetryCount(1);
        existingAnswer.setValid(true);
        existingAnswer.setAnswerJson("""
                {
                  "questionType": "SINGLE_CHOICE",
                  "rawText": "Yes",
                  "normalizedText": "Yes",
                  "selectedOptionId": "%s"
                }
                """.formatted(fixture.yesOption().getId()));

        when(surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(fixture.callAttempt().getId())).thenReturn(Optional.of(existingResponse));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey().getId()))
                .thenReturn(List.of(fixture.choiceQuestion(), fixture.ratingQuestion()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.choiceQuestion().getId()))
                .thenReturn(List.of(fixture.yesOption(), fixture.noOption()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.ratingQuestion().getId()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(existingResponse.getId(), fixture.choiceQuestion().getId()))
                .thenReturn(Optional.of(existingAnswer));
        when(surveyAnswerRepository.findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(existingResponse.getId(), fixture.ratingQuestion().getId()))
                .thenReturn(Optional.empty());

        service.ingest(fixture.callAttempt(), fixture.completedWebhook());

        assertThat(savedAnswers).hasSize(1);
        assertThat(savedAnswers.getFirst().getSurveyQuestion().getCode()).isEqualTo("nps");
        assertThat(existingAnswer.getSelectedOption()).isEqualTo(fixture.yesOption());
        assertThat(existingAnswer.getAnswerText()).isEqualTo("Yes");
    }

    @Test
    void ingest_mapsGenericElevenLabsQuestionResponseKeysByQuestionOrder() {
        TestFixture fixture = buildFixture();
        when(surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(fixture.callAttempt().getId())).thenReturn(Optional.empty());
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey().getId()))
                .thenReturn(List.of(fixture.choiceQuestion(), fixture.ratingQuestion()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.choiceQuestion().getId()))
                .thenReturn(List.of(fixture.yesOption(), fixture.noOption()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.ratingQuestion().getId()))
                .thenReturn(List.of());
        when(surveyAnswerRepository.findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());

        service.ingest(fixture.callAttempt(), fixture.completedWebhookWithGenericQuestionKeys());

        assertThat(savedAnswers).hasSize(2);
        assertThat(savedAnswers)
                .anySatisfy(answer -> {
                    assertThat(answer.getSurveyQuestion().getQuestionOrder()).isEqualTo(1);
                    assertThat(answer.getSelectedOption()).isEqualTo(fixture.yesOption());
                })
                .anySatisfy(answer -> {
                    assertThat(answer.getSurveyQuestion().getQuestionOrder()).isEqualTo(2);
                    assertThat(answer.getAnswerNumber()).isEqualByComparingTo(BigDecimal.valueOf(9));
                });
    }

    @Test
    void ingest_matchesSingleChoiceAnswersDespiteTurkishCharacterDifferences() {
        TurkishChoiceFixture fixture = buildTurkishChoiceFixture();
        CallAttempt mockAttempt = fixture.mockCallAttempt();

        service = new SurveyResponseIngestionServiceImpl(
                new com.yourcompany.surveyai.response.application.provider.ProviderSurveyResultMapperRegistry(List.of(
                        new ElevenLabsSurveyResultMapper(new ObjectMapper()),
                        new MockSurveyResultMapper(new ObjectMapper()),
                        new TurkishMockMapper()
                )),
                surveyResponseRepository,
                surveyAnswerRepository,
                surveyQuestionRepository,
                surveyQuestionOptionRepository,
                new ObjectMapper(),
                providerExecutionObservationService
        );

        when(surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(mockAttempt.getId())).thenReturn(Optional.empty());
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey().getId()))
                .thenReturn(List.of(fixture.choiceQuestion()));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(fixture.choiceQuestion().getId()))
                .thenReturn(List.of(fixture.yesOption(), fixture.noOption()));
        when(surveyAnswerRepository.findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());

        service.ingest(mockAttempt, new ProviderWebhookEvent(
                CallProvider.MOCK,
                mockAttempt.getProviderCallId(),
                mockAttempt.getCallJob().getIdempotencyKey(),
                "mock_webhook",
                CallJobStatus.COMPLETED,
                CallAttemptStatus.COMPLETED,
                OffsetDateTime.now(),
                30,
                new ProviderCorrelationMetadata(null, null, null, null),
                null,
                null,
                null,
                "test transcript",
                "{}"
        ));

        assertThat(savedAnswers)
                .anySatisfy(answer -> {
                    assertThat(answer.getSurveyQuestion().getCode()).isEqualTo("question_2");
                    assertThat(answer.getSelectedOption()).isEqualTo(fixture.noOption());
                    assertThat(answer.getAnswerText()).isEqualTo("Hayir");
                    assertThat(answer.isValid()).isTrue();
                });
    }

    private TestFixture buildFixture() {
        Company company = new Company();
        company.setId(UUID.randomUUID());

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setCompany(company);
        survey.setName("CX");

        Operation operation = new Operation();
        operation.setId(UUID.randomUUID());
        operation.setCompany(company);
        operation.setSurvey(survey);
        operation.setName("April");

        OperationContact contact = new OperationContact();
        contact.setId(UUID.randomUUID());
        contact.setCompany(company);
        contact.setOperation(operation);
        contact.setPhoneNumber("905551112233");
        contact.setStatus(OperationContactStatus.CALLING);
        contact.setRetryCount(0);
        contact.setMetadataJson("{}");

        CallJob callJob = new CallJob();
        callJob.setId(UUID.randomUUID());
        callJob.setCompany(company);
        callJob.setOperation(operation);
        callJob.setOperationContact(contact);
        callJob.setStatus(CallJobStatus.IN_PROGRESS);
        callJob.setPriority((short) 5);
        callJob.setScheduledFor(OffsetDateTime.now());
        callJob.setAvailableAt(OffsetDateTime.now());
        callJob.setAttemptCount(1);
        callJob.setMaxAttempts(3);
        callJob.setIdempotencyKey("op:contact");

        CallAttempt callAttempt = new CallAttempt();
        callAttempt.setId(UUID.randomUUID());
        callAttempt.setCompany(company);
        callAttempt.setCallJob(callJob);
        callAttempt.setOperation(operation);
        callAttempt.setOperationContact(contact);
        callAttempt.setAttemptNumber(1);
        callAttempt.setProvider(CallProvider.ELEVENLABS);
        callAttempt.setProviderCallId("conv_123");
        callAttempt.setStatus(CallAttemptStatus.COMPLETED);
        callAttempt.setDialedAt(OffsetDateTime.now().minusMinutes(2));
        callAttempt.setConnectedAt(OffsetDateTime.now().minusMinutes(1));
        callAttempt.setRawProviderPayload("{}");

        CallAttempt mockCallAttempt = new CallAttempt();
        mockCallAttempt.setId(UUID.randomUUID());
        mockCallAttempt.setCompany(company);
        mockCallAttempt.setCallJob(callJob);
        mockCallAttempt.setOperation(operation);
        mockCallAttempt.setOperationContact(contact);
        mockCallAttempt.setAttemptNumber(2);
        mockCallAttempt.setProvider(CallProvider.MOCK);
        mockCallAttempt.setProviderCallId("mock_123");
        mockCallAttempt.setStatus(CallAttemptStatus.COMPLETED);
        mockCallAttempt.setDialedAt(OffsetDateTime.now().minusMinutes(2));
        mockCallAttempt.setConnectedAt(OffsetDateTime.now().minusMinutes(1));
        mockCallAttempt.setRawProviderPayload("{}");

        SurveyQuestion choiceQuestion = new SurveyQuestion();
        choiceQuestion.setId(UUID.randomUUID());
        choiceQuestion.setCompany(company);
        choiceQuestion.setSurvey(survey);
        choiceQuestion.setCode("consent");
        choiceQuestion.setQuestionOrder(1);
        choiceQuestion.setQuestionType(QuestionType.SINGLE_CHOICE);
        choiceQuestion.setTitle("Do you agree?");

        SurveyQuestion ratingQuestion = new SurveyQuestion();
        ratingQuestion.setId(UUID.randomUUID());
        ratingQuestion.setCompany(company);
        ratingQuestion.setSurvey(survey);
        ratingQuestion.setCode("nps");
        ratingQuestion.setQuestionOrder(2);
        ratingQuestion.setQuestionType(QuestionType.RATING);
        ratingQuestion.setTitle("Rate us");

        SurveyQuestionOption yesOption = new SurveyQuestionOption();
        yesOption.setId(UUID.randomUUID());
        yesOption.setCompany(company);
        yesOption.setSurveyQuestion(choiceQuestion);
        yesOption.setOptionCode("YES");
        yesOption.setLabel("Yes");
        yesOption.setValue("YES");
        yesOption.setOptionOrder(1);
        yesOption.setActive(true);

        SurveyQuestionOption noOption = new SurveyQuestionOption();
        noOption.setId(UUID.randomUUID());
        noOption.setCompany(company);
        noOption.setSurveyQuestion(choiceQuestion);
        noOption.setOptionCode("NO");
        noOption.setLabel("No");
        noOption.setValue("NO");
        noOption.setOptionOrder(2);
        noOption.setActive(true);

        ProviderWebhookEvent completedWebhook = new ProviderWebhookEvent(
                CallProvider.ELEVENLABS,
                "conv_123",
                "op:contact",
                "post_call_transcription",
                CallJobStatus.COMPLETED,
                CallAttemptStatus.COMPLETED,
                OffsetDateTime.now(),
                45,
                new ProviderCorrelationMetadata(
                        operation.getId(),
                        contact.getId(),
                        callJob.getId(),
                        callAttempt.getId()
                ),
                null,
                null,
                "inline://elevenlabs/conversations/conv_123",
                "agent: Hello\nuser: Hi",
                """
                {
                  "type": "post_call_transcription",
                  "data": {
                    "conversation_id": "conv_123",
                    "status": "done",
                    "transcript": [
                      {"role": "agent", "message": "Hello"},
                      {"role": "user", "message": "Hi"}
                    ],
                    "analysis": {
                      "transcript_summary": "Summary",
                      "data_collection_results": {
                        "consent": {"value": "yes"},
                        "nps": {"value": "8", "number": 8}
                      }
                    }
                  }
                }
                """
        );

        ProviderWebhookEvent completedWebhookWithGenericQuestionKeys = new ProviderWebhookEvent(
                CallProvider.ELEVENLABS,
                "conv_123",
                "op:contact",
                "post_call_transcription",
                CallJobStatus.COMPLETED,
                CallAttemptStatus.COMPLETED,
                OffsetDateTime.now(),
                45,
                new ProviderCorrelationMetadata(
                        operation.getId(),
                        contact.getId(),
                        callJob.getId(),
                        callAttempt.getId()
                ),
                null,
                null,
                "inline://elevenlabs/conversations/conv_123",
                "agent: Hello\nuser: Hi",
                """
                {
                  "type": "post_call_transcription",
                  "data": {
                    "conversation_id": "conv_123",
                    "status": "done",
                    "transcript": [
                      {"role": "agent", "message": "Hello"},
                      {"role": "user", "message": "Hi"}
                    ],
                    "analysis": {
                      "transcript_summary": "Summary",
                      "data_collection_results": {
                        "survey_consent_given": {"value": "yes"},
                        "survey_completion_status": {"value": "completed"},
                        "survey_question_1_response": {"value": "yes"},
                        "survey_question_2_response": {"value": "9", "number": 9},
                        "user_feedback_summary": {"value": "Looks good"}
                      }
                    }
                  }
                }
                """
        );

        return new TestFixture(
                company,
                survey,
                operation,
                contact,
                callJob,
                callAttempt,
                mockCallAttempt,
                choiceQuestion,
                ratingQuestion,
                yesOption,
                noOption,
                completedWebhook,
                completedWebhookWithGenericQuestionKeys
        );
    }

    private record TestFixture(
            Company company,
            Survey survey,
            Operation operation,
            OperationContact contact,
            CallJob callJob,
            CallAttempt callAttempt,
            CallAttempt mockCallAttempt,
            SurveyQuestion choiceQuestion,
            SurveyQuestion ratingQuestion,
            SurveyQuestionOption yesOption,
            SurveyQuestionOption noOption,
            ProviderWebhookEvent completedWebhook,
            ProviderWebhookEvent completedWebhookWithGenericQuestionKeys
    ) {
    }

    private TurkishChoiceFixture buildTurkishChoiceFixture() {
        Company company = new Company();
        company.setId(UUID.randomUUID());

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setCompany(company);

        Operation operation = new Operation();
        operation.setId(UUID.randomUUID());
        operation.setCompany(company);
        operation.setSurvey(survey);

        OperationContact contact = new OperationContact();
        contact.setId(UUID.randomUUID());
        contact.setCompany(company);
        contact.setOperation(operation);
        contact.setPhoneNumber("905551112233");
        contact.setStatus(OperationContactStatus.CALLING);
        contact.setRetryCount(0);
        contact.setMetadataJson("{}");

        CallJob callJob = new CallJob();
        callJob.setId(UUID.randomUUID());
        callJob.setCompany(company);
        callJob.setOperation(operation);
        callJob.setOperationContact(contact);
        callJob.setStatus(CallJobStatus.IN_PROGRESS);
        callJob.setPriority((short) 1);
        callJob.setScheduledFor(OffsetDateTime.now());
        callJob.setAvailableAt(OffsetDateTime.now());
        callJob.setAttemptCount(1);
        callJob.setMaxAttempts(3);
        callJob.setIdempotencyKey("turkish-option-test");

        CallAttempt mockCallAttempt = new CallAttempt();
        mockCallAttempt.setId(UUID.randomUUID());
        mockCallAttempt.setCompany(company);
        mockCallAttempt.setCallJob(callJob);
        mockCallAttempt.setOperation(operation);
        mockCallAttempt.setOperationContact(contact);
        mockCallAttempt.setAttemptNumber(1);
        mockCallAttempt.setProvider(CallProvider.MOCK);
        mockCallAttempt.setProviderCallId("mock_turkish_123");
        mockCallAttempt.setStatus(CallAttemptStatus.COMPLETED);
        mockCallAttempt.setDialedAt(OffsetDateTime.now().minusMinutes(2));
        mockCallAttempt.setConnectedAt(OffsetDateTime.now().minusMinutes(1));
        mockCallAttempt.setRawProviderPayload("{}");

        SurveyQuestion choiceQuestion = new SurveyQuestion();
        choiceQuestion.setId(UUID.randomUUID());
        choiceQuestion.setCompany(company);
        choiceQuestion.setSurvey(survey);
        choiceQuestion.setCode("question_2");
        choiceQuestion.setQuestionOrder(2);
        choiceQuestion.setQuestionType(QuestionType.SINGLE_CHOICE);
        choiceQuestion.setTitle("Mevcut belediyeye hiç oy verdiniz mi");

        SurveyQuestionOption yesOption = new SurveyQuestionOption();
        yesOption.setId(UUID.randomUUID());
        yesOption.setCompany(company);
        yesOption.setSurveyQuestion(choiceQuestion);
        yesOption.setOptionCode("option_1");
        yesOption.setLabel("Evet");
        yesOption.setValue("option_1");
        yesOption.setOptionOrder(1);
        yesOption.setActive(true);

        SurveyQuestionOption noOption = new SurveyQuestionOption();
        noOption.setId(UUID.randomUUID());
        noOption.setCompany(company);
        noOption.setSurveyQuestion(choiceQuestion);
        noOption.setOptionCode("option_2");
        noOption.setLabel("Hayir");
        noOption.setValue("option_2");
        noOption.setOptionOrder(2);
        noOption.setActive(true);

        return new TurkishChoiceFixture(survey, mockCallAttempt, choiceQuestion, yesOption, noOption);
    }

    private record TurkishChoiceFixture(
            Survey survey,
            CallAttempt mockCallAttempt,
            SurveyQuestion choiceQuestion,
            SurveyQuestionOption yesOption,
            SurveyQuestionOption noOption
    ) {
    }

    private static class TurkishMockMapper implements ProviderSurveyResultMapper {

        @Override
        public CallProvider getProvider() {
            return CallProvider.MOCK;
        }

        @Override
        public IngestedSurveyResult map(CallAttempt callAttempt, ProviderWebhookEvent event) {
            return new IngestedSurveyResult(
                    SurveyResponseStatus.COMPLETED,
                    BigDecimal.valueOf(100),
                    OffsetDateTime.now().minusMinutes(1),
                    OffsetDateTime.now(),
                    "test transcript",
                    "{}",
                    null,
                    "{}",
                    List.of(
                            new IngestedSurveyAnswer(
                                    null,
                                    2,
                                    "Mevcut belediyeye hiç oy verdiniz mi",
                                    "Hayır",
                                    "Hayır",
                                    "Hayır",
                                    null,
                                    List.of("Hayır"),
                                    null,
                                    true,
                                    null,
                                    "{}"
                            )
                    ),
                    List.of()
            );
        }
    }
}
