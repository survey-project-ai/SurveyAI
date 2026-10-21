package com.yourcompany.surveyai.call.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.dto.request.InterviewAnswerRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewConversationSignal;
import com.yourcompany.surveyai.call.application.dto.request.InterviewFinishRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewInferredAnswer;
import com.yourcompany.surveyai.call.application.dto.request.InterviewSessionRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewVerificationRequest;
import com.yourcompany.surveyai.call.application.dto.response.InterviewOrchestrationResponse;
import com.yourcompany.surveyai.call.application.dto.response.InterviewVerificationResponse;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.operation.application.support.OperationAutoEntityLexiconService;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.response.domain.entity.SurveyAnswer;
import com.yourcompany.surveyai.response.domain.entity.SurveyResponse;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import com.yourcompany.surveyai.response.repository.SurveyAnswerRepository;
import com.yourcompany.surveyai.response.repository.SurveyResponseRepository;
import com.yourcompany.surveyai.survey.application.support.TurkeyGeoDataService;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionOptionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CallInterviewOrchestrationServiceImplTest {

    private final CallAttemptRepository callAttemptRepository = mock(CallAttemptRepository.class);
    private final SurveyResponseRepository surveyResponseRepository = mock(SurveyResponseRepository.class);
    private final SurveyAnswerRepository surveyAnswerRepository = mock(SurveyAnswerRepository.class);
    private final SurveyQuestionRepository surveyQuestionRepository = mock(SurveyQuestionRepository.class);
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository = mock(SurveyQuestionOptionRepository.class);
    private final OperationAutoEntityLexiconService operationAutoEntityLexiconService = new OperationAutoEntityLexiconService(new ObjectMapper(), null, null) {
        @Override
        public List<NamedEntityEntry> extractEntriesForQuestion(String sourcePayloadJson, UUID questionId, String questionCode) {
            return runtimeEntries;
        }
    };

    private CallInterviewOrchestrationServiceImpl service;
    private Fixture fixture;
    private final Map<UUID, SurveyResponse> responsesByAttemptId = new LinkedHashMap<>();
    private final Map<UUID, List<SurveyAnswer>> answersByResponseId = new LinkedHashMap<>();
    private final Map<UUID, List<SurveyQuestionOption>> questionOptionsByQuestionId = new LinkedHashMap<>();
    private List<OperationAutoEntityLexiconService.NamedEntityEntry> runtimeEntries = List.of();

    @BeforeEach
    void setUp() {
        fixture = new Fixture();
        service = new CallInterviewOrchestrationServiceImpl(
                callAttemptRepository,
                surveyResponseRepository,
                surveyAnswerRepository,
                surveyQuestionRepository,
                surveyQuestionOptionRepository,
                new ObjectMapper(),
                new TurkeyGeoDataService(new ObjectMapper()),
                operationAutoEntityLexiconService
        );
        runtimeEntries = List.of();

        when(callAttemptRepository.findAndLockById(fixture.callAttempt.getId()))
                .thenReturn(Optional.of(fixture.callAttempt));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.yesNoQuestion, fixture.openQuestion));
        questionOptionsByQuestionId.clear();
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), List.of(fixture.yesOption, fixture.noOption));
        questionOptionsByQuestionId.put(fixture.openQuestion.getId(), List.of());
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(any()))
                .thenAnswer(invocation -> {
                    Iterable<UUID> questionIds = invocation.getArgument(0);
                    List<SurveyQuestionOption> options = new ArrayList<>();
                    for (UUID questionId : questionIds) {
                        options.addAll(questionOptionsByQuestionId.getOrDefault(questionId, List.of()));
                    }
                    return options;
                });

        when(surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(fixture.callAttempt.getId()))
                .thenAnswer(invocation -> Optional.ofNullable(responsesByAttemptId.get(fixture.callAttempt.getId())));
        when(surveyResponseRepository.save(any(SurveyResponse.class))).thenAnswer(invocation -> {
            SurveyResponse response = invocation.getArgument(0);
            if (response.getId() == null) {
                response.setId(UUID.randomUUID());
            }
            responsesByAttemptId.put(response.getCallAttempt().getId(), response);
            answersByResponseId.putIfAbsent(response.getId(), new ArrayList<>());
            return response;
        });
        when(surveyAnswerRepository.findAllBySurveyResponse_IdAndDeletedAtIsNull(any())).thenAnswer(invocation -> {
            UUID responseId = invocation.getArgument(0);
            return new ArrayList<>(answersByResponseId.getOrDefault(responseId, List.of()));
        });
        when(surveyAnswerRepository.save(any(SurveyAnswer.class))).thenAnswer(invocation -> {
            SurveyAnswer answer = invocation.getArgument(0);
            if (answer.getId() == null) {
                answer.setId(UUID.randomUUID());
            }
            List<SurveyAnswer> answers = answersByResponseId.computeIfAbsent(answer.getSurveyResponse().getId(), ignored -> new ArrayList<>());
            answers.removeIf(existing -> existing.getSurveyQuestion().getId().equals(answer.getSurveyQuestion().getId()));
            answers.add(answer);
            return answer;
        });
    }

    @Test
    void startInterview_createsResponseAndReturnsFirstQuestion() {
        InterviewOrchestrationResponse response = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null)
        );

        assertThat(response.surveyResponseId()).isNotNull();
        assertThat(response.question()).isNotNull();
        assertThat(response.question().conversationQuestionType()).isEqualTo("YES_NO");
        assertThat(response.prompt()).contains("Memnun musunuz?");
    }

    @Test
    void submitAnswer_persistsNormalizedYesNoAndAdvancesToNextQuestion() {
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Evet, memnunum",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(fixture.yesOption);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
        assertThat(response.answeredQuestionCount()).isEqualTo(1);
    }

    @Test
    void identityRequest_explainsReasonForCallAndRepeatsCurrentQuestion() {
        fixture.company.setName("Acme Research");

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Beni neden aradiniz?",
                        InterviewConversationSignal.IDENTITY_REQUEST
                )
        );

        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("satisfied");
        assertThat(response.prompt()).contains("Acme Research");
        assertThat(response.prompt()).doesNotContain("Ayna");
        // The operation name is an internal label and is not read to the callee.
        assertThat(response.prompt()).doesNotContain("April CSAT Wave");
        assertThat(response.prompt()).contains("the CSAT survey");
        assertThat(response.prompt()).contains("Memnun musunuz?");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Cumhuriyet Halk Partisi memnuniyet anketi | Ben Ayna Araştırma adına Cumhuriyet Halk Partisi memnuniyet anketi için arıyorum.",
            "İzmir Gündem Araştırması | Ben Ayna Araştırma adına İzmir Gündem Araştırması için arıyorum.",
            "Belediye hizmetleri | Ben Ayna Araştırma adına Belediye hizmetleri anketi için arıyorum."
    })
    void identityRequest_introducesTheCompanyAndSurveyInTurkish(String surveyName, String expectedIdentity) {
        fixture.survey.setLanguageCode("tr");
        fixture.survey.setName(surveyName);
        fixture.company.setName("Ayna Araştırma");

        InterviewOrchestrationResponse response = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Kimsiniz?", InterviewConversationSignal.IDENTITY_REQUEST));

        assertThat(response.prompt()).startsWith(expectedIdentity);
        assertThat(response.prompt()).doesNotContain("April CSAT Wave");
    }

    @Test
    void reasonRequest_explainsTheQuestionAndAsksItAgainWithoutRecordingAnAnswer() {
        fixture.survey.setLanguageCode("tr");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Bunu neden soruyorsunuz?", InterviewConversationSignal.REASON_REQUEST));

        assertThat(response.question().code()).isEqualTo("satisfied");
        assertThat(response.prompt())
                .startsWith("Bu soruyu anket gereği tüm katılımcılara soruyoruz.")
                .contains("Memnun musunuz?");
        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        assertThat(answersByResponseId.getOrDefault(savedResponse.getId(), List.of())).isEmpty();
    }

    @Test
    void startInterview_doesNotReadYesNoChoicesInSpokenPrompt() {
        InterviewOrchestrationResponse response = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null)
        );

        assertThat(response.prompt()).contains("Memnun musunuz?");
        assertThat(response.prompt()).doesNotContain("Evet").doesNotContain("Hay");
    }

    @Test
    void startInterview_readsTheChoicesOnceAfterTheQuestion() {
        fixture.survey.setLanguageCode("tr");
        fixture.yesNoQuestion.setCode("candidate");
        fixture.yesNoQuestion.setTitle("Cumhurbaşkanı adayı olarak kimi görmek istersiniz? Recep Tayyip Erdoğan, Ekrem İmamoğlu ya da Mansur Yavaş?");
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), List.of(
                buildOption(fixture.yesNoQuestion, 1, "option_1", "Recep Tayyip Erdoğan"),
                buildOption(fixture.yesNoQuestion, 2, "option_2", "Ekrem İmamoğlu"),
                buildOption(fixture.yesNoQuestion, 3, "option_3", "Mansur Yavaş")));

        InterviewOrchestrationResponse response = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        assertThat(response.prompt())
                .contains("Cumhurbaşkanı adayı olarak kimi görmek istersiniz? Recep Tayyip Erdoğan, Ekrem İmamoğlu ya da Mansur Yavaş?");
        // The list written into the title is replaced, not read twice.
        assertThat(response.prompt().split("Mansur Yavaş", -1)).hasSize(2);
    }

    @Test
    void startInterview_readsTheChoicesInEnglish() {
        fixture.yesNoQuestion.setTitle("How satisfied are you?");
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), List.of(
                buildOption(fixture.yesNoQuestion, 1, "option_1", "Very satisfied"),
                buildOption(fixture.yesNoQuestion, 2, "option_2", "Neutral"),
                buildOption(fixture.yesNoQuestion, 3, "option_3", "Not satisfied")));

        InterviewOrchestrationResponse response = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        assertThat(response.prompt()).contains("How satisfied are you? Very satisfied, Neutral, or Not satisfied?");
    }

    @Test
    void startInterview_doesNotReadTheChoicesWhenTheQuestionSaysNotTo() {
        fixture.yesNoQuestion.setTitle("Hangi partiye oy verirdiniz?");
        fixture.yesNoQuestion.setSettingsJson("{\"readOptions\":false}");
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), List.of(
                buildOption(fixture.yesNoQuestion, 1, "option_1", "Birinci Parti"),
                buildOption(fixture.yesNoQuestion, 2, "option_2", "İkinci Parti"),
                buildOption(fixture.yesNoQuestion, 3, "option_3", "Üçüncü Parti")));

        InterviewOrchestrationResponse response = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        assertThat(response.prompt()).contains("Hangi partiye oy verirdiniz?").doesNotContain("Parti,");
    }

    @Test
    void startInterview_readsMoreThanFiveChoicesOnlyWhenTheQuestionSaysTo() {
        fixture.yesNoQuestion.setTitle("Hangi ilçede yaşıyorsunuz?");
        List<SurveyQuestionOption> districts = List.of("Konak", "Bornova", "Karşıyaka", "Buca", "Bayraklı", "Çiğli").stream()
                .map(name -> buildOption(fixture.yesNoQuestion, 1, name.toLowerCase(), name))
                .toList();
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), districts);

        String byDefault = service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null)).prompt();
        fixture.yesNoQuestion.setSettingsJson("{\"readOptions\":true}");
        String whenAsked = service.getCurrentQuestion(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null)).prompt();

        assertThat(byDefault).doesNotContain("Konak");
        assertThat(whenAsked).contains("Konak, Bornova, Karşıyaka, Buca, Bayraklı, or Çiğli?");
    }

    @Test
    void startInterview_doesNotReadNumericBrackets() {
        fixture.yesNoQuestion.setCode("age_group");
        fixture.yesNoQuestion.setTitle("Kaç yaşındasınız?");
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), List.of(
                buildOption(fixture.yesNoQuestion, 1, "option_1", "18-24"),
                buildOption(fixture.yesNoQuestion, 2, "option_2", "25-34"),
                buildOption(fixture.yesNoQuestion, 3, "option_3", "35 ve üzeri")));

        InterviewOrchestrationResponse response = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        assertThat(response.prompt()).contains("Kaç yaşındasınız?").doesNotContain("18-24");
    }

    @Test
    void repeatRequest_repeatsTheQuestionWithWordsMeantToBeSpoken() {
        fixture.survey.setLanguageCode("tr");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Tekrar eder misiniz?", InterviewConversationSignal.REPEAT_REQUEST));

        // Our LiveKit agent speaks the prompt as it is; it used to be an instruction to the ElevenLabs LLM.
        assertThat(response.prompt()).startsWith("Tabii, tekrar soruyorum.").contains("Memnun musunuz?");
    }

    @Test
    void unclearAnswerAfterTheLastRetry_movesOnWithWordsMeantToBeSpoken() {
        fixture.survey.setLanguageCode("tr");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        // Two different replies: the same words again within seconds count as a doubled tool call.
        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Ulasim sorunlarini cozmesi", InterviewConversationSignal.ANSWER));
        InterviewOrchestrationResponse response = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Trafik cok kotu", InterviewConversationSignal.ANSWER));

        assertThat(response.question().code()).isEqualTo("why");
        assertThat(response.prompt()).startsWith("Sizi net anlayamadım, bir sonraki soruya geçiyorum.");
    }

    @Test
    void stopRequest_endsTheCallWithAShortGoodbye() {
        fixture.survey.setLanguageCode("tr");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Şu an meşgulüm, kapatıyorum", InterviewConversationSignal.STOP_REQUEST));

        assertThat(response.endCall()).isTrue();
        assertThat(response.surveyStatus()).isEqualTo(SurveyResponseStatus.ABANDONED);
        assertThat(response.closingMessage()).isEqualTo("Anladım, teşekkür ederim. İyi günler dilerim.");
    }

    @Test
    void genderQuestion_isNotAskedOnLiveKitCalls() {
        fixture.callAttempt.setProvider(CallProvider.LIVEKIT);
        useGenderQuestion(null);
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse afterFirst = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Evet", InterviewConversationSignal.ANSWER));
        InterviewOrchestrationResponse afterLast = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Ulasim sorunlarini cozmesi", InterviewConversationSignal.ANSWER));

        assertThat(afterFirst.question().code()).isEqualTo("why");
        assertThat(afterLast.surveyStatus()).isEqualTo(SurveyResponseStatus.COMPLETED);
    }

    @Test
    void genderQuestion_isAskedOnElevenLabsCallsAndWhenTheQuestionSaysSo() {
        useGenderQuestion(null);
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));
        InterviewOrchestrationResponse elevenLabs = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Evet", InterviewConversationSignal.ANSWER));

        assertThat(elevenLabs.question().code()).isEqualTo("gender");

        fixture.callAttempt.setProvider(CallProvider.LIVEKIT);
        useGenderQuestion("{\"fromVoice\":false}");
        assertThat(service.getCurrentQuestion(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null))
                .question().code()).isEqualTo("gender");
    }

    @Test
    void finishInterview_recordsTheGenderTheAgentHeardEvenAfterTheInterviewEnded() throws Exception {
        fixture.callAttempt.setProvider(CallProvider.LIVEKIT);
        SurveyQuestion gender = useGenderQuestion(null);
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));
        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Evet", InterviewConversationSignal.ANSWER));
        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Ulasim sorunlarini cozmesi", InterviewConversationSignal.ANSWER));

        InterviewOrchestrationResponse finished = service.finishInterview(new InterviewFinishRequest(
                fixture.callAttempt.getId(), null, null, null, true,
                Map.of("gender", new InterviewInferredAnswer("kadın", 0.976))));

        SurveyResponse response = responsesByAttemptId.get(fixture.callAttempt.getId());
        SurveyAnswer genderAnswer = answersByResponseId.get(response.getId()).stream()
                .filter(answer -> answer.getSurveyQuestion() == gender)
                .findFirst()
                .orElseThrow();
        assertThat(finished.surveyStatus()).isEqualTo(SurveyResponseStatus.COMPLETED);
        assertThat(genderAnswer.getSelectedOption().getLabel()).isEqualTo("Kadın");
        assertThat(genderAnswer.isValid()).isTrue();
        assertThat(new ObjectMapper().readTree(genderAnswer.getAnswerJson()).path("source").asText()).isEqualTo("voice");
        assertThat(fixture.contact.getGender()).isEqualTo("Kadın");
    }

    @Test
    void finishInterview_keepsTheGenderFromTheContactList() {
        fixture.callAttempt.setProvider(CallProvider.LIVEKIT);
        fixture.contact.setGender("Erkek");
        useGenderQuestion(null);
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.finishInterview(new InterviewFinishRequest(fixture.callAttempt.getId(), null, null, null, true,
                Map.of("gender", new InterviewInferredAnswer("kadın", 0.9))));

        assertThat(fixture.contact.getGender()).isEqualTo("Erkek");
    }

    @Test
    void verification_confirmsAnAnswerBothChecksHeardTheSame() throws Exception {
        SurveyAnswer rating = answerRating("Dört.");

        InterviewVerificationResponse.Result result = verifyRating("Dört.", "dört");

        assertThat(result.status()).isEqualTo("verified");
        assertThat(rating.getAnswerNumber()).isEqualByComparingTo("4");
        assertThat(new ObjectMapper().readTree(rating.getAnswerJson()).path("verification").path("status").asText())
                .isEqualTo("verified");
    }

    @Test
    void verification_correctsAnAnswerBothChecksHeardDifferentlyAndKeepsTheCallsValue() throws Exception {
        // The call heard "beş" where the callee said "dört".
        SurveyAnswer rating = answerRating("Beş.");

        InterviewVerificationResponse.Result result = verifyRating("Dört.", "dört");

        assertThat(result.status()).isEqualTo("corrected");
        assertThat(result.recorded()).isEqualTo("5");
        assertThat(result.suggested()).isEqualTo("4");
        assertThat(rating.getAnswerNumber()).isEqualByComparingTo("4");
        JsonNode verification = new ObjectMapper().readTree(rating.getAnswerJson()).path("verification");
        assertThat(verification.path("original").path("answerNumber").decimalValue()).isEqualByComparingTo("5");
        assertThat(verification.path("checks")).hasSize(2);
    }

    @Test
    void verification_sendsAnAnswerToAPersonWhenTheChecksDisagree() {
        SurveyAnswer rating = answerRating("Beş.");

        InterviewVerificationResponse.Result result = verifyRating("Dört.", "Üç.");

        assertThat(result.status()).isEqualTo("review");
        assertThat(result.reason()).isEqualTo("checks_disagree");
        assertThat(rating.getAnswerNumber()).isEqualByComparingTo("5");
    }

    @Test
    void verification_doesNotFillInAnAnswerTheCallMissed() {
        SurveyAnswer rating = answerRating("Mavi gömlekli olan");

        InterviewVerificationResponse.Result result = verifyRating("Dört.", "dört");

        assertThat(result.status()).isEqualTo("review");
        assertThat(result.reason()).isEqualTo("missed_in_call");
        assertThat(result.suggested()).isEqualTo("4");
        assertThat(rating.isValid()).isFalse();
    }

    @Test
    void verification_leavesACorrectionToAPersonWhenOtherQuestionsBranchOnTheAnswer() {
        fixture.openQuestion.setBranchConditionJson("""
                {"mode":"ASK_IF","rules":[{"questionCode":"%s","operator":"GREATER_THAN","value":3}]}
                """.formatted(fixture.ratingQuestion.getCode()));
        SurveyAnswer rating = answerRating("Beş.");

        InterviewVerificationResponse.Result result = verifyRating("Dört.", "dört");

        assertThat(result.status()).isEqualTo("review");
        assertThat(result.reason()).isEqualTo("affects_branching");
        assertThat(rating.getAnswerNumber()).isEqualByComparingTo("5");
    }

    private SurveyAnswer answerRating(String utterance) {
        fixture.survey.setLanguageCode("tr");
        fixture.ratingQuestion.setSettingsJson("{\"min\":1,\"max\":5}");
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.ratingQuestion, fixture.openQuestion));
        questionOptionsByQuestionId.put(fixture.ratingQuestion.getId(), List.of());
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));
        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, utterance, InterviewConversationSignal.ANSWER));
        SurveyResponse response = responsesByAttemptId.get(fixture.callAttempt.getId());
        return answersByResponseId.get(response.getId()).stream()
                .filter(answer -> answer.getSurveyQuestion() == fixture.ratingQuestion)
                .findFirst()
                .orElseThrow();
    }

    private InterviewVerificationResponse.Result verifyRating(String firstCheck, String secondCheck) {
        Map<String, String> transcripts = new LinkedHashMap<>();
        transcripts.put("speechmatics/linden-1", firstCheck);
        transcripts.put("google/gemini-3.5-transcribe-live", secondCheck);
        InterviewVerificationResponse response = service.recordVerification(new InterviewVerificationRequest(
                fixture.callAttempt.getId(), null,
                List.of(new InterviewVerificationRequest.AnswerCheck(fixture.ratingQuestion.getId(), "live", transcripts))));
        assertThat(response.results()).hasSize(1);
        return response.results().getFirst();
    }

    private SurveyQuestion useGenderQuestion(String settingsJson) {
        SurveyQuestion gender = buildQuestion("gender", 2, QuestionType.SINGLE_CHOICE, "Cinsiyetiniz nedir?");
        if (settingsJson != null) {
            gender.setSettingsJson(settingsJson);
        }
        fixture.openQuestion.setQuestionOrder(3);
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.yesNoQuestion, gender, fixture.openQuestion));
        questionOptionsByQuestionId.put(gender.getId(), List.of(
                buildOption(gender, 1, "female", "Kadın"),
                buildOption(gender, 2, "male", "Erkek")));
        return gender;
    }

    @Test
    void interviewTools_acceptLiveKitCallAttempts() {
        fixture.callAttempt.setProvider(CallProvider.LIVEKIT);

        InterviewOrchestrationResponse response = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        assertThat(response.question().code()).isEqualTo("satisfied");
    }

    @Test
    void interviewTools_findALiveKitCallByItsRoomName() {
        fixture.callAttempt.setProvider(CallProvider.LIVEKIT);
        when(callAttemptRepository.findAndLockByProviderCallId(CallProvider.ELEVENLABS, "call-room")).thenReturn(Optional.empty());
        when(callAttemptRepository.findAndLockByProviderCallId(CallProvider.LIVEKIT, "call-room"))
                .thenReturn(Optional.of(fixture.callAttempt));

        InterviewOrchestrationResponse response = service.startInterview(new InterviewSessionRequest(null, "call-room", null));

        assertThat(response.callAttemptId()).isEqualTo(fixture.callAttempt.getId());
    }

    @Test
    void interviewTools_rejectCallsOfOtherProviders() {
        fixture.callAttempt.setProvider(CallProvider.MOCK);

        assertThatThrownBy(() -> service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void startInterview_deliversIntroOnlyAfterGreeting() {
        fixture.survey.setIntroPrompt("Izmir Gundem Arastirmasi icin sizi ariyorum.");

        InterviewOrchestrationResponse startResponse = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null)
        );
        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Merhaba",
                        InterviewConversationSignal.ANSWER
                )
        );

        assertThat(startResponse.prompt()).isNull();
        assertThat(startResponse.question()).isNull();
        assertThat(response.prompt()).contains("Memnun musunuz?");
        assertThat(response.prompt()).contains("Izmir Gundem Arastirmasi icin sizi ariyorum.");
    }

    @Test
    void submitAnswer_treatsAnyClearOpeningUtteranceAsGreeting() {
        fixture.survey.setIntroPrompt("Kisa bir anketimize katilmak ister miydiniz?");

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Bir saniye",
                        InterviewConversationSignal.ANSWER
                )
        );

        assertThat(response.question()).isNull();
        assertThat(response.prompt()).contains("Kisa bir anketimize katilmak ister miydiniz?");
    }

    @Test
    void submitAnswer_consumesConsentReplyWithoutRepeatingOpeningPrompt() {
        fixture.survey.setIntroPrompt("Kisa bir anketimize katilmak ister miydiniz?");

        InterviewOrchestrationResponse introResponse = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Alo",
                        InterviewConversationSignal.ANSWER
                )
        );

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Evet, buyurun",
                        InterviewConversationSignal.ANSWER
                )
        );

        assertThat(introResponse.question()).isNull();
        assertThat(introResponse.prompt()).contains("Kisa bir anketimize katilmak ister miydiniz?");
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("satisfied");
        assertThat(response.prompt()).contains("Memnun musunuz?");
        assertThat(response.prompt()).doesNotContain("Kisa bir anketimize katilmak ister miydiniz?");
    }

    @Test
    void getCurrentQuestion_returnsOpeningPromptDuringGreetingOrConsentPhase() {
        fixture.survey.setIntroPrompt("Kisa bir anketimize katilmak ister miydiniz?");

        InterviewOrchestrationResponse greetingPhase = service.getCurrentQuestion(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null)
        );

        assertThat(greetingPhase.question()).isNull();
        assertThat(greetingPhase.prompt()).contains("Kisa bir anketimize katilmak ister miydiniz?");

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Alo",
                        InterviewConversationSignal.ANSWER
                )
        );

        InterviewOrchestrationResponse consentPhase = service.getCurrentQuestion(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null)
        );

        assertThat(consentPhase.question()).isNull();
        assertThat(consentPhase.prompt()).contains("Kisa bir anketimize katilmak ister miydiniz?");
    }
    
    @Test
    void invalidRatingAnswer_retriesBeforeMovingForward() {
        fixture.ratingQuestion.setSettingsJson("{\"min\":1,\"max\":5}");
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.ratingQuestion, fixture.openQuestion));
        questionOptionsByQuestionId.put(fixture.ratingQuestion.getId(), List.of());

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "sekiz",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isFalse();
        assertThat(answers.getFirst().getRetryCount()).isEqualTo(1);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo(fixture.ratingQuestion.getCode());
        // The retry prompt says what is wanted; the stored reason is not read out in front of it.
        assertThat(response.prompt()).contains("Please say one number between 1 and 5").doesNotContain("Rating answer must be between");
        assertThat(answers.getFirst().getInvalidReason()).isEqualTo("Rating answer must be between 1 and 5");
    }

    @Test
    void invalidRatingAnswer_retriesInTurkishWithoutEnglishWords() {
        fixture.survey.setLanguageCode("tr");
        fixture.ratingQuestion.setSettingsJson("{\"min\":1,\"max\":5}");
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.ratingQuestion, fixture.openQuestion));
        questionOptionsByQuestionId.put(fixture.ratingQuestion.getId(), List.of());
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "sekiz", InterviewConversationSignal.ANSWER));

        assertThat(response.prompt())
                .startsWith("Yanıtı puan olarak anlayamadım. Lütfen 1 ile 5 arasında tek bir sayı söyleyin.")
                .doesNotContain("Rating");
    }

    @Test
    void ratingQuestion_endsEachSentenceOnceInEnglish() {
        fixture.ratingQuestion.setTitle("How would you rate us?");
        fixture.ratingQuestion.setSettingsJson("{\"min\":1,\"max\":5}");
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.ratingQuestion, fixture.openQuestion));
        questionOptionsByQuestionId.put(fixture.ratingQuestion.getId(), List.of());

        InterviewOrchestrationResponse response = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        assertThat(response.prompt()).isEqualTo("How would you rate us? Please answer with one number between 1 and 5.");
    }

    @Test
    void ratingQuestion_doesNotRepeatAScaleTheTitleAlreadyGives() {
        fixture.survey.setLanguageCode("tr");
        fixture.ratingQuestion.setTitle("Belediye hizmetlerinden ne kadar memnunsunuz? Bir ile beş arasında bir puan verir misiniz?");
        fixture.ratingQuestion.setSettingsJson("{\"min\":1,\"max\":5}");
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.ratingQuestion, fixture.openQuestion));
        questionOptionsByQuestionId.put(fixture.ratingQuestion.getId(), List.of());

        InterviewOrchestrationResponse response = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        assertThat(response.prompt())
                .isEqualTo("Belediye hizmetlerinden ne kadar memnunsunuz? Bir ile beş arasında bir puan verir misiniz?");
    }

    @Test
    void numberQuestion_isAskedWithoutAnInstructionUntilAnAnswerIsUnclear() {
        fixture.survey.setLanguageCode("tr");
        SurveyQuestion ageQuestion = buildQuestion("age", 1, QuestionType.NUMBER, "Kaç yaşındasınız?");
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(ageQuestion, fixture.openQuestion));
        questionOptionsByQuestionId.put(ageQuestion.getId(), List.of());

        InterviewOrchestrationResponse first = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));
        InterviewOrchestrationResponse retry = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Mavi gömlekli olan", InterviewConversationSignal.ANSWER));

        assertThat(first.prompt()).isEqualTo("Kaç yaşındasınız?");
        assertThat(retry.prompt()).contains("tek bir sayı").endsWith("Kaç yaşındasınız?");
    }

    @Test
    void invalidOptionalSingleChoiceAnswer_retriesInsteadOfSkippingForward() {
        fixture.yesNoQuestion.setRequired(false);

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Ulasim sorunlarini cozmesi",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isFalse();
        assertThat(answers.getFirst().getRetryCount()).isEqualTo(1);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("satisfied");
        assertThat(response.prompt()).contains("Answer did not match");
    }

    @Test
    void semanticRatingPhrase_mapsToHighestRatingWithoutClarification() {
        fixture.ratingQuestion.setSettingsJson("{\"min\":1,\"max\":5}");
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.ratingQuestion, fixture.openQuestion));
        questionOptionsByQuestionId.put(fixture.ratingQuestion.getId(), List.of());

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Oldukca onemli",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getAnswerNumber()).isEqualByComparingTo("5");
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void matrixRatingPrompt_readsSharedDescriptionOnlyOnFirstRow() {
        String sharedDescription = "5 = Cok onemli, 1 = Hic onemli degil araliginda belirtiniz.";

        SurveyQuestion firstMatrixRow = buildQuestion(
                "priority_ekonomi",
                1,
                QuestionType.RATING,
                "Ekonomi " + sharedDescription
        );
        firstMatrixRow.setSettingsJson("""
                {
                  "groupCode":"priority",
                  "groupTitle":"Asagidaki konularin onem duzeyini degerlendirin.",
                  "rowLabel":"Ekonomi 5 = Cok onemli, 1 = Hic onemli degil araliginda belirtiniz.",
                  "matrixType":"GRID_RATING",
                  "matrixDescription":"5 = Cok onemli, 1 = Hic onemli degil araliginda belirtiniz.",
                  "min":1,
                  "max":5
                }
                """);

        SurveyQuestion secondMatrixRow = buildQuestion(
                "priority_adalet",
                2,
                QuestionType.RATING,
                "Adalet " + sharedDescription
        );
        secondMatrixRow.setSettingsJson("""
                {
                  "groupCode":"priority",
                  "groupTitle":"Asagidaki konularin onem duzeyini degerlendirin.",
                  "rowLabel":"Adalet 5 = Cok onemli, 1 = Hic onemli degil araliginda belirtiniz.",
                  "matrixType":"GRID_RATING",
                  "matrixDescription":"5 = Cok onemli, 1 = Hic onemli degil araliginda belirtiniz.",
                  "min":1,
                  "max":5
                }
                """);

        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(firstMatrixRow, secondMatrixRow, fixture.openQuestion));
        questionOptionsByQuestionId.put(firstMatrixRow.getId(), List.of());
        questionOptionsByQuestionId.put(secondMatrixRow.getId(), List.of());

        InterviewOrchestrationResponse startResponse = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null)
        );

        assertThat(countOccurrences(startResponse.prompt(), sharedDescription)).isEqualTo(1);
        assertThat(startResponse.prompt()).contains("Ekonomi");

        InterviewOrchestrationResponse nextResponse = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "5",
                        InterviewConversationSignal.ANSWER
                )
        );

        assertThat(nextResponse.question()).isNotNull();
        assertThat(nextResponse.question().code()).isEqualTo("priority_adalet");
        assertThat(countOccurrences(nextResponse.prompt(), sharedDescription)).isZero();
        assertThat(nextResponse.prompt()).contains("Adalet");
    }

    @Test
    void submitAnswer_matchesChoiceAliasesFromQuestionSettings() {
        fixture.yesNoQuestion.setTitle("Levent Uysal'i ne derece taniyorsunuz?");
        fixture.yesNoQuestion.setSettingsJson("""
                {
                  "aliases": {
                    "taniyorum": ["taniyorum", "biliyorum"],
                    "hic_duymadim": ["hic duymadim", "tanimiyorum"]
                  }
                }
                """);
        fixture.yesOption.setOptionCode("taniyorum");
        fixture.yesOption.setLabel("Taniyorum");
        fixture.yesOption.setValue("taniyorum");
        fixture.noOption.setOptionCode("hic_duymadim");
        fixture.noOption.setLabel("Hic duymadim");
        fixture.noOption.setValue("hic_duymadim");

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "biliyorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(fixture.yesOption);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_matchesSingleChoiceFromNaturalSentence() {
        fixture.yesNoQuestion.setTitle("Levent Uysal'i ne derece taniyorsunuz?");
        fixture.yesOption.setOptionCode("taniyorum");
        fixture.yesOption.setLabel("Taniyorum");
        fixture.yesOption.setValue("taniyorum");
        fixture.noOption.setOptionCode("tanimiyorum");
        fixture.noOption.setLabel("Tanimiyorum");
        fixture.noOption.setValue("tanimiyorum");

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Evet, onu taniyorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(fixture.yesOption);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_mapsBareNegativeReplyToKnowledgeScaleOption() {
        fixture.yesNoQuestion.setTitle("Levent Uysal'i ne derece taniyorsunuz?");
        fixture.yesOption.setOptionCode("taniyorum");
        fixture.yesOption.setLabel("Taniyorum");
        fixture.yesOption.setValue("taniyorum");
        fixture.noOption.setOptionCode("duydum_ama_tanimiyorum");
        fixture.noOption.setLabel("Duydum ama tanimiyorum");
        fixture.noOption.setValue("duydum_ama_tanimiyorum");

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Hayir",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(fixture.noOption);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_matchesTurkishCharactersInKnowledgeAnswer() {
        fixture.yesNoQuestion.setTitle("Ali Mahir Basarir'i ne derece taniyorsunuz?");
        fixture.yesOption.setOptionCode("taniyorum");
        fixture.yesOption.setLabel("Taniyorum");
        fixture.yesOption.setValue("taniyorum");
        fixture.noOption.setOptionCode("duydum_ama_tanimiyorum");
        fixture.noOption.setLabel("Duydum ama tanimiyorum");
        fixture.noOption.setValue("duydum_ama_tanimiyorum");

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Tan\u0131m\u0131yorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(fixture.noOption);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_matchesConfiguredSpecialAnswerAndAdvances() {
        fixture.yesNoQuestion.setSettingsJson("""
                {
                  "specialAnswers": {
                    "bilmiyorum": ["bilmiyorum", "fikrim yok"]
                  }
                }
                """);

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "fikrim yok",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isNull();
        assertThat(answers.getFirst().getAnswerText()).isEqualTo("Bilmiyorum");
        assertThat(answers.getFirst().getAnswerJson()).contains("\"specialAnswerCode\":\"bilmiyorum\"");
        assertThat(answers.getFirst().getAnswerJson()).contains("\"normalizedValues\":[\"bilmiyorum\"]");
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_matchesElectionPreferenceFromNaturalSentence() {
        fixture.yesNoQuestion.setCode("question_3");
        fixture.yesNoQuestion.setTitle("Partiye gore mi yoksa adaylara gore mi oy kullaniyorsunuz?");
        fixture.yesOption.setOptionCode("option_1");
        fixture.yesOption.setLabel("Partiye Gore");
        fixture.yesOption.setValue("option_1");
        fixture.noOption.setOptionCode("option_3");
        fixture.noOption.setLabel("Her ikisi de");
        fixture.noOption.setValue("option_3");

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Ya, her ikisine gore de oy kullaniyorum.",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(fixture.noOption);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_matchesElectionPreferenceFromDirectPartyAnswer() {
        fixture.yesNoQuestion.setCode("question_3");
        fixture.yesNoQuestion.setTitle("Milletvekili secimlerinde, genel secimlerde partiye gore mi yoksa milletvekili adaylarina gore mi oy kullaniyorsunuz?");

        SurveyQuestionOption partyOption = buildOption(fixture.yesNoQuestion, 1, "option_1", "Partiye Gore");
        SurveyQuestionOption candidateOption = buildOption(fixture.yesNoQuestion, 2, "option_2", "Adaylara gore");
        SurveyQuestionOption bothOption = buildOption(fixture.yesNoQuestion, 3, "option_3", "Her ikisi de");
        SurveyQuestionOption noOpinionOption = buildOption(fixture.yesNoQuestion, 4, "option_4", "Cevap yok/Fikrim yok");
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), List.of(partyOption, candidateOption, bothOption, noOpinionOption));

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Partiye gore.",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(partyOption);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void startInterview_stripsInlineChoiceEnumerationFromSpokenPrompt() {
        fixture.yesNoQuestion.setCode("education_level");
        fixture.yesNoQuestion.setTitle("Ogrenim durumunuz nedir? Ilkokul, ortaokul, lise, universite, yuksek lisans veya okur yazar degil misiniz?");

        SurveyQuestionOption primary = buildOption(fixture.yesNoQuestion, 1, "option_1", "Ilkokul");
        SurveyQuestionOption middle = buildOption(fixture.yesNoQuestion, 2, "option_2", "Ortaokul");
        SurveyQuestionOption high = buildOption(fixture.yesNoQuestion, 3, "option_3", "Lise");
        SurveyQuestionOption university = buildOption(fixture.yesNoQuestion, 4, "option_4", "Universite / Lisans");
        SurveyQuestionOption master = buildOption(fixture.yesNoQuestion, 5, "option_5", "Yuksek Lisans");
        SurveyQuestionOption illiterate = buildOption(fixture.yesNoQuestion, 6, "option_6", "Okur Yazar Degil");
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), List.of(primary, middle, high, university, master, illiterate));

        InterviewOrchestrationResponse response = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null)
        );

        assertThat(response.prompt()).contains("Ogrenim durumunuz nedir?");
        assertThat(response.prompt())
                .doesNotContain("Ilkokul")
                .doesNotContain("ortaokul")
                .doesNotContain("yuksek lisans")
                .doesNotContain("okur yazar degil");
        assertThat(response.question()).isNotNull();
        assertThat(response.question().options()).hasSize(6);
    }

    @Test
    void submitAnswer_matchesShortEducationLevelUtterance() {
        fixture.yesNoQuestion.setCode("education_level");
        fixture.yesNoQuestion.setTitle("Ogrenim durumunuz nedir?");

        SurveyQuestionOption primary = buildOption(fixture.yesNoQuestion, 1, "option_1", "Ilkokul");
        SurveyQuestionOption middle = buildOption(fixture.yesNoQuestion, 2, "option_2", "Ortaokul");
        SurveyQuestionOption high = buildOption(fixture.yesNoQuestion, 3, "option_3", "Lise");
        SurveyQuestionOption university = buildOption(fixture.yesNoQuestion, 4, "option_4", "Universite / Lisans");
        SurveyQuestionOption master = buildOption(fixture.yesNoQuestion, 5, "option_5", "Yuksek Lisans");
        SurveyQuestionOption illiterate = buildOption(fixture.yesNoQuestion, 6, "option_6", "Okur Yazar Degil");
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), List.of(primary, middle, high, university, master, illiterate));

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Eee, universite.",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(university);
        assertThat(response.prompt()).doesNotContain("Hangisi daha");
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_matchesPoliticalCandidateUsingGeneratedAliases() {
        fixture.yesNoQuestion.setCode("aday_tercihi");
        fixture.yesNoQuestion.setTitle("Bu pazar secim olsa hangi adaya oy verirsiniz?");
        fixture.yesNoQuestion.setSettingsJson("""
                {
                  "autoAliases": {
                    "ali_mahir_basarir": ["Ali Mahir Başarır", "Ali Mahir", "Mahir Başarır", "Başarır"],
                    "veli_ucar": ["Veli Uçar", "Veli", "Uçar"]
                  }
                }
                """);
        fixture.yesOption.setOptionCode("ali_mahir_basarir");
        fixture.yesOption.setLabel("Ali Mahir Başarır");
        fixture.yesOption.setValue("ali_mahir_basarir");
        fixture.noOption.setOptionCode("veli_ucar");
        fixture.noOption.setLabel("Veli Uçar");
        fixture.noOption.setValue("veli_ucar");

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Sanirim Halim Ahir Basarir'a daha yakinim",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(fixture.yesOption);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_mapsNumericAgeIntoConfiguredBucket() {
        fixture.yesNoQuestion.setCode("demografi_yas");
        fixture.yesNoQuestion.setTitle("Gorusulen kisinin yasi");
        fixture.yesOption.setOptionCode("35_49");
        fixture.yesOption.setLabel("35-49");
        fixture.yesOption.setValue("35_49");
        fixture.noOption.setOptionCode("50_64");
        fixture.noOption.setLabel("50-64");
        fixture.noOption.setValue("50_64");

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "kirk sekiz",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(fixture.yesOption);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_parsesSpokenNumberForNumberQuestion() {
        fixture.yesNoQuestion.setCode("demografi_yas");
        fixture.yesNoQuestion.setTitle("Yasinizi ogrenebilir miyim?");
        fixture.yesNoQuestion.setQuestionType(QuestionType.NUMBER);

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "yirmi yedi",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getAnswerNumber()).isEqualByComparingTo("27");
        assertThat(answers.getFirst().getAnswerText()).isEqualTo("27");
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_matchesDefaultSpecialAnswerWithoutQuestionConfiguration() {
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "cevap vermek istemiyorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isNull();
        assertThat(answers.getFirst().getAnswerJson()).contains("\"specialAnswerCode\":\"reddetti\"");
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
    }

    @ParameterizedTest
    @CsvSource({
            "tabii ki hayır, false",
            "doğru değil, false",
            "uygun değil, false",
            "hayır istemiyorum, false",
            "tabii, true",
            "evet doğru, true"
    })
    void submitAnswer_readsNegationInYesNoAnswers(String utterance, boolean expectYes) {
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, utterance, InterviewConversationSignal.ANSWER));

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        SurveyAnswer answer = answersByResponseId.get(savedResponse.getId()).getFirst();
        assertThat(answer.getAnswerJson()).contains("\"specialAnswerCode\":null");
        assertThat(answer.getSelectedOption()).isEqualTo(expectYes ? fixture.yesOption : fixture.noOption);
    }

    @ParameterizedTest
    @CsvSource({
            "pek memnun değilim, Memnun değilim",
            "ben çok memnunum, Çok memnunum",
            "memnunum, Memnunum",
            "hiç memnun değilim, Hiç memnun değilim"
    })
    void submitAnswer_picksTheMostSpecificScaleOption(String utterance, String expectedLabel) {
        fixture.yesNoQuestion.setTitle("Hizmetten ne kadar memnunsunuz?");
        List<SurveyQuestionOption> scale = List.of(
                buildOption(fixture.yesNoQuestion, 1, "option_1", "Hiç memnun değilim"),
                buildOption(fixture.yesNoQuestion, 2, "option_2", "Memnun değilim"),
                buildOption(fixture.yesNoQuestion, 3, "option_3", "Kararsızım"),
                buildOption(fixture.yesNoQuestion, 4, "option_4", "Memnunum"),
                buildOption(fixture.yesNoQuestion, 5, "option_5", "Çok memnunum")
        );
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), scale);
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, utterance, InterviewConversationSignal.ANSWER));

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        SurveyAnswer answer = answersByResponseId.get(savedResponse.getId()).getFirst();
        assertThat(answer.isValid()).isTrue();
        assertThat(answer.getSelectedOption().getLabel()).isEqualTo(expectedLabel);
    }

    @ParameterizedTest
    @CsvSource({
            "önemli değil, 2",
            "çok önemli değil, 2",
            "çok önemli, 5",
            "hiç önemli değil, 1"
    })
    void submitAnswer_readsNegatedImportanceRatings(String utterance, int expectedRating) {
        fixture.ratingQuestion.setSettingsJson("{\"min\":1,\"max\":5}");
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.ratingQuestion, fixture.openQuestion));
        questionOptionsByQuestionId.put(fixture.ratingQuestion.getId(), List.of());
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, utterance, InterviewConversationSignal.ANSWER));

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        SurveyAnswer answer = answersByResponseId.get(savedResponse.getId()).getFirst();
        assertThat(answer.isValid()).isTrue();
        assertThat(answer.getAnswerNumber()).isEqualByComparingTo(String.valueOf(expectedRating));
    }

    @ParameterizedTest
    @CsvSource({
            "on beş, 15",
            "on sekiz yaşındayım, 18",
            "yüz elli, 150",
            "1985, 1985",
            "bin dokuz yüz seksen beş, 1985"
    })
    void submitAnswer_readsSpokenNumbers(String utterance, int expected) {
        fixture.yesNoQuestion.setQuestionType(QuestionType.NUMBER);
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, utterance, InterviewConversationSignal.ANSWER));

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        SurveyAnswer answer = answersByResponseId.get(savedResponse.getId()).getFirst();
        assertThat(answer.isValid()).isTrue();
        assertThat(answer.getAnswerNumber()).isEqualByComparingTo(String.valueOf(expected));
    }

    @ParameterizedTest
    @CsvSource({
            "10 üzerinden 8, 10, 8",
            "on üzerinden yedi, 10, 7",
            "bir dört diyelim, 5, 4"
    })
    void submitAnswer_readsScoresGivenOutOfAScale(String utterance, int max, int expected) {
        fixture.ratingQuestion.setSettingsJson("{\"min\":1,\"max\":" + max + "}");
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.ratingQuestion, fixture.openQuestion));
        questionOptionsByQuestionId.put(fixture.ratingQuestion.getId(), List.of());
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, utterance, InterviewConversationSignal.ANSWER));

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        SurveyAnswer answer = answersByResponseId.get(savedResponse.getId()).getFirst();
        assertThat(answer.isValid()).isTrue();
        assertThat(answer.getAnswerNumber()).isEqualByComparingTo(String.valueOf(expected));
    }

    @ParameterizedTest
    @CsvSource({
            "yetmiş yaşındayım, 65+",
            "65, 65+",
            "altmış, 55-64",
            "yirmi beş, 25-34"
    })
    void submitAnswer_placesAgeInTheRightBucket(String utterance, String expectedLabel) {
        fixture.yesNoQuestion.setCode("age_group");
        fixture.yesNoQuestion.setTitle("Kaç yaşındasınız?");
        List<String> buckets = List.of("18-24", "25-34", "35-44", "45-54", "55-64", "65+");
        List<SurveyQuestionOption> options = new ArrayList<>();
        for (int index = 0; index < buckets.size(); index++) {
            options.add(buildOption(fixture.yesNoQuestion, index + 1, "option_" + (index + 1), buckets.get(index)));
        }
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), options);
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, utterance, InterviewConversationSignal.ANSWER));

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        SurveyAnswer answer = answersByResponseId.get(savedResponse.getId()).getFirst();
        assertThat(answer.isValid()).isTrue();
        assertThat(answer.getSelectedOption().getLabel()).isEqualTo(expectedLabel);
    }

    @Test
    void submitAnswer_treatsNoThatOneAsRejectionOfTheClarification() {
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.openQuestion));
        fixture.openQuestion.setRequired(true);
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));
        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());

        SurveyAnswer pendingClarification = new SurveyAnswer();
        pendingClarification.setId(UUID.randomUUID());
        pendingClarification.setCompany(fixture.company);
        pendingClarification.setSurveyResponse(savedResponse);
        pendingClarification.setSurveyQuestion(fixture.openQuestion);
        pendingClarification.setAnswerType(QuestionType.OPEN_ENDED);
        pendingClarification.setValid(false);
        pendingClarification.setRetryCount(1);
        pendingClarification.setAnswerJson("{\"clarificationPrompt\":\"Mansur Yavaş mi demek istediniz?\"}");
        answersByResponseId.get(savedResponse.getId()).add(pendingClarification);

        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Hayır, o değil", InterviewConversationSignal.ANSWER));

        SurveyAnswer answer = answersByResponseId.get(savedResponse.getId()).getFirst();
        assertThat(answer.isValid()).isFalse();
        assertThat(answer.getAnswerText()).isNotEqualTo("Mansur Yavaş");
    }

    @Test
    void submitAnswer_appliesAnAnswerSubmittedTwiceInARowOnlyOnce() {
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse first = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "evet", InterviewConversationSignal.ANSWER));
        // A doubled or retried tool call repeats the same utterance right away.
        InterviewOrchestrationResponse second = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "evet", InterviewConversationSignal.ANSWER));

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().getSurveyQuestion()).isEqualTo(fixture.yesNoQuestion);
        assertThat(first.question().code()).isEqualTo("why");
        assertThat(second.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_appliesTheSameWordsAgainOnceTheRepeatWindowHasPassed() {
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));
        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "evet", InterviewConversationSignal.ANSWER));

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        savedResponse.setTranscriptJson(savedResponse.getTranscriptJson()
                .replaceAll("\"receivedAtEpochMillis\":\\d+", "\"receivedAtEpochMillis\":0"));

        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "evet", InterviewConversationSignal.ANSWER));

        assertThat(answersByResponseId.get(savedResponse.getId()))
                .extracting(answer -> answer.getSurveyQuestion().getCode())
                .containsExactlyInAnyOrder(fixture.yesNoQuestion.getCode(), "why");
    }

    @Test
    void submitAnswer_ignoresAnswerForAQuestionThatIsNoLongerCurrent() {
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));
        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "evet", InterviewConversationSignal.ANSWER));

        InterviewOrchestrationResponse response = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(),
                null,
                null,
                "Fiyatlar uygun",
                InterviewConversationSignal.ANSWER,
                fixture.yesNoQuestion.getId()
        ));

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        assertThat(answersByResponseId.get(savedResponse.getId())).hasSize(1);
        assertThat(response.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_stillMatchesDefaultSpecialAnswerInsideLongerReply() {
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "valla hic bilmiyorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getAnswerJson()).contains("\"specialAnswerCode\":\"bilmiyorum\"");
    }

    @Test
    void submitAnswer_doesNotMatchSpecialAliasInsideAnotherWord() {
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.openQuestion));
        fixture.openQuestion.setRequired(true);

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Bayrampaşa'da oturduğum için",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getAnswerJson()).contains("\"specialAnswerCode\":null");
        assertThat(answers.getFirst().getAnswerText()).contains("Bayrampaşa");
    }

    @Test
    void submitAnswer_doesNotTreatShortReplyContainedInLongerAliasAsRefusal() {
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "istiyorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.getOrDefault(savedResponse.getId(), List.of());

        assertThat(answers).noneMatch(answer -> answer.getAnswerJson() != null
                && answer.getAnswerJson().contains("\"specialAnswerCode\":\"reddetti\""));
    }

    @Test
    void submitAnswer_mapsBareYokToNoOptionInsteadOfSpecialAnswer() {
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "yok",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().getAnswerJson()).contains("\"specialAnswerCode\":null");
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(fixture.noOption);
    }

    @Test
    void submitAnswer_keepsNamedPartyOnQuestionThatListsParties() {
        fixture.yesNoQuestion.setCode("parti_tercihi");
        fixture.yesNoQuestion.setTitle("Bu pazar secim olsa hangi partiye oy verirsiniz?");

        SurveyQuestionOption akParti = buildOption(fixture.yesNoQuestion, 1, "option_1", "AK Parti");
        SurveyQuestionOption chp = buildOption(fixture.yesNoQuestion, 2, "option_2", "CHP");
        SurveyQuestionOption mhp = buildOption(fixture.yesNoQuestion, 3, "option_3", "MHP");
        SurveyQuestionOption iyiParti = buildOption(fixture.yesNoQuestion, 4, "option_4", "İYİ Parti");
        SurveyQuestionOption demParti = buildOption(fixture.yesNoQuestion, 5, "option_5", "DEM Parti");
        questionOptionsByQuestionId.put(fixture.yesNoQuestion.getId(), List.of(akParti, chp, mhp, iyiParti, demParti));

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "İYİ Parti'ye oy veririm",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getSelectedOption()).isEqualTo(iyiParti);
    }

    @Test
    void submitAnswer_ignoresAsrArtifactsAndRepeatsCurrentQuestion() {
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.openQuestion));
        fixture.openQuestion.setRequired(true);

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Egitim.<ltr>",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isFalse();
        assertThat(answers.getFirst().getRetryCount()).isEqualTo(1);
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
        assertThat(response.prompt()).contains("No clear answer captured from the caller");
    }

    @Test
    void submitAnswer_addsConfiguredOpenEndedCodingThemesToAnswerJson() {
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.openQuestion));
        fixture.openQuestion.setRequired(true);
        fixture.openQuestion.setSettingsJson("""
                {
                  "coding": {
                    "categories": {
                      "ulasim": ["ulasim", "trafik", "yol"],
                      "ekonomi": ["ekonomi", "issizlik"]
                    }
                  }
                }
                """);

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Ulasim ve trafik en buyuk sorun",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().getAnswerJson()).contains("\"codedThemes\":[\"ulasim\"]");
    }

    @Test
    void startInterview_withPermissionStyleIntroWaitsForConsentBeforeFirstQuestion() {
        fixture.survey.setIntroPrompt("Merhaba, Ayna Arastirma adina ariyorum. Size birkac soru sorabilir miyim?");

        InterviewOrchestrationResponse response = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null)
        );

        assertThat(response.prompt()).isNull();
        assertThat(response.question()).isNull();
        assertThat(response.endCall()).isFalse();
    }

    @Test
    void submitAnswer_positiveConsentAdvancesToFirstQuestionWithoutSavingSurveyAnswer() {
        fixture.survey.setIntroPrompt("Merhaba, Ayna Arastirma adina ariyorum. Size birkac soru sorabilir miyim?");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse introResponse = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Alo",
                        InterviewConversationSignal.ANSWER
                )
        );

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Evet, sorabilirsiniz",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(introResponse.question()).isNull();
        assertThat(introResponse.prompt()).contains("Size birkac soru sorabilir miyim?");
        assertThat(answers).isEmpty();
        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("satisfied");
        assertThat(response.prompt()).contains("Memnun musunuz?");
    }

    @Test
    void submitAnswer_unclearConsentRepeatsOpeningPromptInsteadOfAdvancing() {
        fixture.survey.setIntroPrompt("Merhaba, Ayna Arastirma adina ariyorum. Size birkac soru sorabilir miyim?");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse firstResponse = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Alo",
                        InterviewConversationSignal.ANSWER
                )
        );

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Merhaba",
                        InterviewConversationSignal.ANSWER
                )
        );

        assertThat(firstResponse.question()).isNull();
        assertThat(firstResponse.prompt()).contains("Size birkac soru sorabilir miyim?");
        assertThat(response.question()).isNull();
        assertThat(response.prompt()).contains("Size birkac soru sorabilir miyim?");
    }

    @Test
    void submitAnswer_initializesConsentFlowEvenIfStartInterviewWasNotCalled() {
        fixture.survey.setIntroPrompt("Merhaba, Ayna Arastirma adina ariyorum. Size birkac soru sorabilir miyim?");

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Evet",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).isEmpty();
        assertThat(response.question()).isNull();
        assertThat(response.prompt()).contains("Size birkac soru sorabilir miyim?");
    }

    @Test
    void submitAnswer_singleNoWordDuringConsentDoesNotEndCall() {
        fixture.survey.setIntroPrompt("Merhaba, Ayna Arastirma adina ariyorum. Size birkac soru sorabilir miyim?");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Alo",
                        InterviewConversationSignal.ANSWER
                )
        );

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Hayir",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());

        assertThat(savedResponse.getStatus()).isEqualTo(com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus.PARTIAL);
        assertThat(response.endCall()).isFalse();
        assertThat(response.question()).isNull();
        assertThat(response.prompt()).contains("Size birkac soru sorabilir miyim?");
    }

    @Test
    void submitAnswer_explicitDeclineDuringConsentEndsCall() {
        fixture.survey.setIntroPrompt("Merhaba, Ayna Arastirma adina ariyorum. Size birkac soru sorabilir miyim?");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Alo",
                        InterviewConversationSignal.ANSWER
                )
        );

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Katilmak istemiyorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(savedResponse.getStatus()).isEqualTo(com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus.ABANDONED);
        assertThat(answers).isEmpty();
        assertThat(response.endCall()).isTrue();
        assertThat(response.prompt()).isNotBlank();
    }

    @Test
    void submitAnswer_declineConsentWithoutQuestionMarkInIntroDoesNotSaveFirstQuestionAnswer() {
        fixture.survey.setIntroPrompt("Merhaba, kisa bir anketimize katilir misin");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Alo",
                        InterviewConversationSignal.ANSWER
                )
        );

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Katilmak istemiyorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(savedResponse.getStatus()).isEqualTo(com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus.ABANDONED);
        assertThat(answers).isEmpty();
        assertThat(response.endCall()).isTrue();
    }

    @Test
    void submitAnswer_ignoresAnyFollowUpUtteranceAfterConsentDecline() {
        fixture.survey.setIntroPrompt("Merhaba, kisa bir anketimize katilir misin");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Alo",
                        InterviewConversationSignal.ANSWER
                )
        );

        service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Katilmak istemiyorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        InterviewOrchestrationResponse followUpResponse = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Hayir",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(savedResponse.getStatus()).isEqualTo(com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus.ABANDONED);
        assertThat(answers).isEmpty();
        assertThat(followUpResponse.endCall()).isTrue();
        assertThat(followUpResponse.question()).isNull();
    }

    @Test
    void finishInterview_stripsVoiceDirectionTagsFromClosingPrompt() {
        fixture.survey.setClosingPrompt("[sad] Peki, [slow] tesekkur ederim. [happy] Iyi gunler dilerim.");

        InterviewOrchestrationResponse response = service.finishInterview(
                new com.yourcompany.surveyai.call.application.dto.request.InterviewFinishRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        null,
                        null
                )
        );

        assertThat(response.prompt()).isEqualTo("Peki, tesekkur ederim. Iyi gunler dilerim.");
        assertThat(response.closingMessage()).isEqualTo("Peki, tesekkur ederim. Iyi gunler dilerim.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Hayır, ilgilenmiyorum | DECLINED",
            "Şu an müsait değilim | DECLINED",
            "Evet ama şimdi olmaz | DECLINED",
            "Hayır, sorun değil, buyurun | ACCEPTED",
            "Tabii ki | ACCEPTED",
            "Olur | ACCEPTED",
            "Evet | ACCEPTED",
            // A bare no depends on how the intro was phrased, so the question is asked again.
            "Hayır | UNCLEAR",
            "Yok | UNCLEAR",
            "Kim olursunuz | UNCLEAR"
    })
    void submitAnswer_readsTheConsentDecision(String utterance, String expected) {
        fixture.survey.setIntroPrompt("Kisa bir anketimize katilmak ister miydiniz?");
        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Alo", InterviewConversationSignal.ANSWER));

        InterviewOrchestrationResponse response = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, utterance, InterviewConversationSignal.ANSWER));

        switch (expected) {
            case "ACCEPTED" -> {
                assertThat(response.endCall()).isFalse();
                assertThat(response.question().code()).isEqualTo("satisfied");
            }
            case "DECLINED" -> {
                assertThat(response.endCall()).isTrue();
                assertThat(response.surveyStatus()).isEqualTo(SurveyResponseStatus.ABANDONED);
                assertThat(responsesByAttemptId.get(fixture.callAttempt.getId()).getTranscriptJson())
                        .contains("\"openingConsentState\":\"DECLINED\"");
            }
            default -> {
                assertThat(response.endCall()).isFalse();
                assertThat(response.question()).isNull();
                assertThat(response.prompt()).contains("Kisa bir anketimize katilmak ister miydiniz?");
            }
        }
    }

    @Test
    void submitAnswer_endsTheCallWhenConsentStaysUnclearAfterTheRetries() {
        fixture.survey.setIntroPrompt("Kisa bir anketimize katilmak ister miydiniz?");
        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Alo", InterviewConversationSignal.ANSWER));

        List<InterviewOrchestrationResponse> replies = new ArrayList<>();
        for (String utterance : List.of("Hayir", "Efendim, anlamadim", "Ne dediniz")) {
            replies.add(service.submitAnswer(new InterviewAnswerRequest(
                    fixture.callAttempt.getId(), null, null, utterance, InterviewConversationSignal.ANSWER)));
        }

        assertThat(replies.get(0).endCall()).isFalse();
        assertThat(replies.get(0).question()).isNull();
        assertThat(replies.get(0).prompt()).contains("Kisa bir anketimize katilmak ister miydiniz?");
        assertThat(replies.get(1).endCall()).isFalse();
        assertThat(replies.get(2).endCall()).isTrue();
        assertThat(replies.get(2).surveyStatus()).isEqualTo(SurveyResponseStatus.ABANDONED);
        assertThat(replies.get(2).closingMessage()).isNotBlank();
        assertThat(responsesByAttemptId.get(fixture.callAttempt.getId()).getTranscriptJson())
                .contains("\"openingConsentState\":\"UNCLEAR\"");
    }

    @Test
    void recoverFailedAnswer_asksTheQuestionAgainAndMovesOnOnceTheRetriesAreUsed() {
        fixture.survey.setLanguageCode("tr");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse firstFailure = service.recoverFailedAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Evet memnunum", InterviewConversationSignal.ANSWER));

        assertThat(firstFailure.endCall()).isFalse();
        assertThat(firstFailure.question().code()).isEqualTo("satisfied");
        assertThat(firstFailure.prompt()).startsWith("Kusura bakmayın, cevabınızı tam anlayamadım.").contains("Memnun musunuz?");
        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        SurveyAnswer failedAnswer = answersByResponseId.get(savedResponse.getId()).getFirst();
        assertThat(failedAnswer.isValid()).isFalse();
        assertThat(failedAnswer.getRetryCount()).isEqualTo(1);
        assertThat(failedAnswer.getRawInputText()).isEqualTo("Evet memnunum");

        // The same failure again uses up the retries, so the interview moves on instead of looping.
        InterviewOrchestrationResponse secondFailure = service.recoverFailedAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Evet, memnunum dedim", InterviewConversationSignal.ANSWER));

        assertThat(secondFailure.question().code()).isNotEqualTo("satisfied");
    }

    @Test
    void recoverFailedAnswer_duringTheConsentPhaseRepeatsTheConsentQuestion() {
        fixture.survey.setIntroPrompt("Kisa bir anketimize katilmak ister miydiniz?");
        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Alo", InterviewConversationSignal.ANSWER));

        InterviewOrchestrationResponse response = service.recoverFailedAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Evet", InterviewConversationSignal.ANSWER));

        assertThat(response.question()).isNull();
        assertThat(response.prompt()).contains("Kisa bir anketimize katilmak ister miydiniz?");
        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        assertThat(answersByResponseId.getOrDefault(savedResponse.getId(), List.of())).isEmpty();
    }

    @Test
    void startAndFinish_doNotReopenAnInterviewWhoseConsentWasDeclined() {
        fixture.survey.setIntroPrompt("Kisa bir anketimize katilmak ister miydiniz?");
        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Alo", InterviewConversationSignal.ANSWER));
        InterviewOrchestrationResponse declined = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Katilmak istemiyorum", InterviewConversationSignal.ANSWER));
        assertThat(declined.surveyStatus()).isEqualTo(SurveyResponseStatus.ABANDONED);

        InterviewOrchestrationResponse restarted = service.startInterview(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));
        InterviewOrchestrationResponse finished = service.finishInterview(
                new com.yourcompany.surveyai.call.application.dto.request.InterviewFinishRequest(
                        fixture.callAttempt.getId(), null, null, null, null));

        assertThat(restarted.endCall()).isTrue();
        assertThat(restarted.question()).isNull();
        assertThat(restarted.surveyStatus()).isEqualTo(SurveyResponseStatus.ABANDONED);
        assertThat(finished.surveyStatus()).isEqualTo(SurveyResponseStatus.ABANDONED);
        assertThat(finished.closingMessage()).isEqualTo(declined.closingMessage());
        assertThat(responsesByAttemptId.get(fixture.callAttempt.getId()).getStatus()).isEqualTo(SurveyResponseStatus.ABANDONED);
    }

    @Test
    void finishInterview_doesNotTurnACompletedInterviewIntoAbandoned() {
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));
        InterviewOrchestrationResponse response = null;
        for (String utterance : List.of("Evet", "4", "Ulasim daha iyi olsun")) {
            response = service.submitAnswer(new InterviewAnswerRequest(
                    fixture.callAttempt.getId(), null, null, utterance, InterviewConversationSignal.ANSWER));
        }
        assertThat(response.surveyStatus()).isEqualTo(SurveyResponseStatus.COMPLETED);

        InterviewOrchestrationResponse finished = service.finishInterview(
                new com.yourcompany.surveyai.call.application.dto.request.InterviewFinishRequest(
                        fixture.callAttempt.getId(), null, null, SurveyResponseStatus.ABANDONED, true));

        assertThat(finished.surveyStatus()).isEqualTo(SurveyResponseStatus.COMPLETED);
        assertThat(finished.closingMessage()).isNull();
        assertThat(responsesByAttemptId.get(fixture.callAttempt.getId()).getStatus()).isEqualTo(SurveyResponseStatus.COMPLETED);
    }

    @Test
    void getCurrentQuestion_beforeTheGreetingRecordsTheOpeningSoTheNextReplyAnswersTheQuestion() {
        fixture.survey.setIntroPrompt("Izmir Gundem Arastirmasi icin sizi ariyorum.");

        InterviewOrchestrationResponse opening = service.getCurrentQuestion(
                new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));
        InterviewOrchestrationResponse next = service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Evet", InterviewConversationSignal.ANSWER));

        assertThat(opening.prompt()).contains("Izmir Gundem Arastirmasi icin sizi ariyorum.").contains("Memnun musunuz?");
        assertThat(next.prompt()).doesNotContain("Izmir Gundem Arastirmasi icin sizi ariyorum.");
        assertThat(next.question().code()).isNotEqualTo("satisfied");
        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        SurveyAnswer answer = answersByResponseId.get(savedResponse.getId()).getFirst();
        assertThat(answer.getSurveyQuestion().getCode()).isEqualTo("satisfied");
        assertThat(answer.isValid()).isTrue();
    }

    @Test
    void finishInterview_suppressesClosingPromptWhenRequested() {
        fixture.survey.setClosingPrompt("[sad] Peki, [slow] tesekkur ederim. [happy] Iyi gunler dilerim.");

        InterviewOrchestrationResponse response = service.finishInterview(
                new com.yourcompany.surveyai.call.application.dto.request.InterviewFinishRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        null,
                        true
                )
        );

        assertThat(response.endCall()).isTrue();
        assertThat(response.prompt()).isNull();
        assertThat(response.closingMessage()).isNull();
    }

    @Test
    void submitAnswer_skipsMatrixFollowUpRowWhenBranchSkipRuleMatchesSameRow() {
        SurveyQuestion familiarityQuestion = buildQuestion("b2_levent", 1, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece taniyorsunuz?");
        familiarityQuestion.setSettingsJson("""
                {"groupCode":"B2","rowCode":"levent_uysal"}
                """);

        SurveyQuestion favorabilityQuestion = buildQuestion("b3_levent", 2, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece begeniyorsunuz?");
        favorabilityQuestion.setSettingsJson("""
                {"groupCode":"B3","rowCode":"levent_uysal"}
                """);
        favorabilityQuestion.setBranchConditionJson("""
                {
                  "skipIf": {
                    "groupCode": "B2",
                    "sameRowCode": true,
                    "selectedOptionCodes": ["duydum_ama_tanimiyorum", "hic_duymadim"]
                  }
                }
                """);

        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(familiarityQuestion, favorabilityQuestion, fixture.openQuestion));

        SurveyQuestionOption taniyorumOption = buildOption(familiarityQuestion, 1, "taniyorum", "Taniyorum");
        SurveyQuestionOption hicDuymadimOption = buildOption(familiarityQuestion, 2, "hic_duymadim", "Hic duymadim");
        SurveyQuestionOption begeniyorumOption = buildOption(favorabilityQuestion, 1, "begeniyorum", "Begeniyorum");
        SurveyQuestionOption begenmiyorumOption = buildOption(favorabilityQuestion, 2, "begenmiyorum", "Begenmiyorum");

        questionOptionsByQuestionId.put(familiarityQuestion.getId(), List.of(taniyorumOption, hicDuymadimOption));
        questionOptionsByQuestionId.put(favorabilityQuestion.getId(), List.of(begeniyorumOption, begenmiyorumOption));
        questionOptionsByQuestionId.put(fixture.openQuestion.getId(), List.of());

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Hic duymadim",
                        InterviewConversationSignal.ANSWER
                )
        );

        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
        assertThat(response.totalQuestionCount()).isEqualTo(2);
    }

    @Test
    void submitAnswer_keepsMatrixFollowUpRowWhenBranchSkipRuleDoesNotMatch() {
        SurveyQuestion familiarityQuestion = buildQuestion("b2_levent", 1, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece taniyorsunuz?");
        familiarityQuestion.setSettingsJson("""
                {"groupCode":"B2","rowCode":"levent_uysal"}
                """);

        SurveyQuestion favorabilityQuestion = buildQuestion("b3_levent", 2, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece begeniyorsunuz?");
        favorabilityQuestion.setSettingsJson("""
                {"groupCode":"B3","rowCode":"levent_uysal"}
                """);
        favorabilityQuestion.setBranchConditionJson("""
                {
                  "skipIf": {
                    "groupCode": "B2",
                    "sameRowCode": true,
                    "selectedOptionCodes": ["duydum_ama_tanimiyorum", "hic_duymadim"]
                  }
                }
                """);

        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(familiarityQuestion, favorabilityQuestion, fixture.openQuestion));

        SurveyQuestionOption taniyorumOption = buildOption(familiarityQuestion, 1, "taniyorum", "Taniyorum");
        SurveyQuestionOption hicDuymadimOption = buildOption(familiarityQuestion, 2, "hic_duymadim", "Hic duymadim");
        SurveyQuestionOption begeniyorumOption = buildOption(favorabilityQuestion, 1, "begeniyorum", "Begeniyorum");
        SurveyQuestionOption begenmiyorumOption = buildOption(favorabilityQuestion, 2, "begenmiyorum", "Begenmiyorum");

        questionOptionsByQuestionId.put(familiarityQuestion.getId(), List.of(taniyorumOption, hicDuymadimOption));
        questionOptionsByQuestionId.put(favorabilityQuestion.getId(), List.of(begeniyorumOption, begenmiyorumOption));
        questionOptionsByQuestionId.put(fixture.openQuestion.getId(), List.of());

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Taniyorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("b3_levent");
        assertThat(response.totalQuestionCount()).isEqualTo(3);
    }

    @Test
    void submitAnswer_skipsMatrixFollowUpRowWhenLegacyBranchStoresMatrixReferenceInQuestionCode() {
        SurveyQuestion familiarityQuestion = buildQuestion("b2_levent", 1, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece taniyorsunuz?");
        familiarityQuestion.setSettingsJson("""
                {"groupCode":"B2","rowCode":"levent_uysal"}
                """);

        SurveyQuestion favorabilityQuestion = buildQuestion("b3_levent", 2, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece begeniyorsunuz?");
        favorabilityQuestion.setSettingsJson("""
                {"groupCode":"B3","rowCode":"levent_uysal"}
                """);
        favorabilityQuestion.setBranchConditionJson("""
                {
                  "skipIf": {
                    "questionCode": "B2",
                    "sameRowCode": true,
                    "selectedOptionCodes": ["duydum_ama_tanimiyorum", "hic_duymadim"]
                  }
                }
                """);

        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(familiarityQuestion, favorabilityQuestion, fixture.openQuestion));

        SurveyQuestionOption taniyorumOption = buildOption(familiarityQuestion, 1, "taniyorum", "Taniyorum");
        SurveyQuestionOption hicDuymadimOption = buildOption(familiarityQuestion, 2, "hic_duymadim", "Hic duymadim");
        SurveyQuestionOption begeniyorumOption = buildOption(favorabilityQuestion, 1, "begeniyorum", "Begeniyorum");
        SurveyQuestionOption begenmiyorumOption = buildOption(favorabilityQuestion, 2, "begenmiyorum", "Begenmiyorum");

        questionOptionsByQuestionId.put(familiarityQuestion.getId(), List.of(taniyorumOption, hicDuymadimOption));
        questionOptionsByQuestionId.put(favorabilityQuestion.getId(), List.of(begeniyorumOption, begenmiyorumOption));
        questionOptionsByQuestionId.put(fixture.openQuestion.getId(), List.of());

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Hic duymadim",
                        InterviewConversationSignal.ANSWER
                )
        );

        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
        assertThat(response.totalQuestionCount()).isEqualTo(2);
    }

    @Test
    void submitAnswer_asksMatrixFollowUpRowWhenAskIfUsesKnowledgePositiveTag() {
        SurveyQuestion familiarityQuestion = buildQuestion("b2_levent", 1, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece taniyorsunuz?");
        familiarityQuestion.setSettingsJson("""
                {"groupCode":"B2","rowCode":"levent_uysal"}
                """);

        SurveyQuestion favorabilityQuestion = buildQuestion("b3_levent", 2, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece begeniyorsunuz?");
        favorabilityQuestion.setSettingsJson("""
                {"groupCode":"B3","rowCode":"levent_uysal"}
                """);
        favorabilityQuestion.setBranchConditionJson("""
                {
                  "askIf": {
                    "groupCode": "B2",
                    "sameRowCode": true,
                    "answerTagsAnyOf": ["knowledge_positive"]
                  }
                }
                """);

        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(familiarityQuestion, favorabilityQuestion, fixture.openQuestion));

        SurveyQuestionOption birazTaniyorumOption = buildOption(familiarityQuestion, 1, "biraz_taniyorum", "Biraz taniyorum");
        SurveyQuestionOption tanimiyorumOption = buildOption(familiarityQuestion, 2, "tanimiyorum", "Tanimiyorum");
        SurveyQuestionOption begeniyorumOption = buildOption(favorabilityQuestion, 1, "begeniyorum", "Begeniyorum");
        SurveyQuestionOption begenmiyorumOption = buildOption(favorabilityQuestion, 2, "begenmiyorum", "Begenmiyorum");

        questionOptionsByQuestionId.put(familiarityQuestion.getId(), List.of(birazTaniyorumOption, tanimiyorumOption));
        questionOptionsByQuestionId.put(favorabilityQuestion.getId(), List.of(begeniyorumOption, begenmiyorumOption));
        questionOptionsByQuestionId.put(fixture.openQuestion.getId(), List.of());

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Biraz taniyorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("b3_levent");
        assertThat(response.totalQuestionCount()).isEqualTo(3);
    }

    @Test
    void submitAnswer_skipsMatrixFollowUpRowWhenLegacyNegativeOptionsOnlyMatchBySemanticTag() {
        SurveyQuestion familiarityQuestion = buildQuestion("b2_levent", 1, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece taniyorsunuz?");
        familiarityQuestion.setSettingsJson("""
                {"groupCode":"B2","rowCode":"levent_uysal"}
                """);

        SurveyQuestion favorabilityQuestion = buildQuestion("b3_levent", 2, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece begeniyorsunuz?");
        favorabilityQuestion.setSettingsJson("""
                {"groupCode":"B3","rowCode":"levent_uysal"}
                """);
        favorabilityQuestion.setBranchConditionJson("""
                {
                  "skipIf": {
                    "groupCode": "B2",
                    "sameRowCode": true,
                    "selectedOptionCodes": ["duydum_ama_tanimiyorum", "hic_duymadim"]
                  }
                }
                """);

        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(familiarityQuestion, favorabilityQuestion, fixture.openQuestion));

        SurveyQuestionOption tanimiyorumOption = buildOption(familiarityQuestion, 1, "tanimiyorum", "Tanimiyorum");
        SurveyQuestionOption taniyorumOption = buildOption(familiarityQuestion, 2, "taniyorum", "Taniyorum");
        SurveyQuestionOption begeniyorumOption = buildOption(favorabilityQuestion, 1, "begeniyorum", "Begeniyorum");
        SurveyQuestionOption begenmiyorumOption = buildOption(favorabilityQuestion, 2, "begenmiyorum", "Begenmiyorum");

        questionOptionsByQuestionId.put(familiarityQuestion.getId(), List.of(tanimiyorumOption, taniyorumOption));
        questionOptionsByQuestionId.put(favorabilityQuestion.getId(), List.of(begeniyorumOption, begenmiyorumOption));
        questionOptionsByQuestionId.put(fixture.openQuestion.getId(), List.of());

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Tanimiyorum",
                        InterviewConversationSignal.ANSWER
                )
        );

        assertThat(response.question()).isNotNull();
        assertThat(response.question().code()).isEqualTo("why");
        assertThat(response.totalQuestionCount()).isEqualTo(2);
    }

    @Test
    void submitAnswer_onlyAsksLeventFavorabilityWhenLeventKnownAndAliUnknown() {
        SurveyQuestion familiarityLevent = buildQuestion("b2_levent", 1, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece taniyorsunuz?");
        familiarityLevent.setSettingsJson("""
                {"groupCode":"B2","rowCode":"levent_uysal"}
                """);
        SurveyQuestion familiarityAli = buildQuestion("b2_ali", 2, QuestionType.SINGLE_CHOICE, "Ali Mahir Basarir'i ne derece taniyorsunuz?");
        familiarityAli.setSettingsJson("""
                {"groupCode":"B2","rowCode":"ali_mahir_basarir"}
                """);

        SurveyQuestion favorabilityLevent = buildQuestion("b3_levent", 3, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece begeniyorsunuz?");
        favorabilityLevent.setSettingsJson("""
                {"groupCode":"B3","rowCode":"levent_uysal"}
                """);
        favorabilityLevent.setBranchConditionJson("""
                {
                  "askIf": {
                    "groupCode": "B2",
                    "sameRowCode": true,
                    "answerTagsAnyOf": ["knowledge_positive"]
                  }
                }
                """);
        SurveyQuestion favorabilityAli = buildQuestion("b3_ali", 4, QuestionType.SINGLE_CHOICE, "Ali Mahir Basarir'i ne derece begeniyorsunuz?");
        favorabilityAli.setSettingsJson("""
                {"groupCode":"B3","rowCode":"ali_mahir_basarir"}
                """);
        favorabilityAli.setBranchConditionJson(favorabilityLevent.getBranchConditionJson());

        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(familiarityLevent, familiarityAli, favorabilityLevent, favorabilityAli, fixture.openQuestion));

        SurveyQuestionOption leventTaniyorumOption = buildOption(familiarityLevent, 1, "taniyorum", "Taniyorum");
        SurveyQuestionOption leventTanimiyorumOption = buildOption(familiarityLevent, 2, "tanimiyorum", "Tanimiyorum");
        SurveyQuestionOption leventHicDuymadimOption = buildOption(familiarityLevent, 3, "hic_duymadim", "Hic duymadim");
        SurveyQuestionOption aliTaniyorumOption = buildOption(familiarityAli, 1, "taniyorum", "Taniyorum");
        SurveyQuestionOption aliTanimiyorumOption = buildOption(familiarityAli, 2, "tanimiyorum", "Tanimiyorum");
        SurveyQuestionOption aliHicDuymadimOption = buildOption(familiarityAli, 3, "hic_duymadim", "Hic duymadim");
        SurveyQuestionOption leventBegeniyorumOption = buildOption(favorabilityLevent, 1, "begeniyorum", "Begeniyorum");
        SurveyQuestionOption leventBegenmiyorumOption = buildOption(favorabilityLevent, 2, "begenmiyorum", "Begenmiyorum");
        SurveyQuestionOption aliBegeniyorumOption = buildOption(favorabilityAli, 1, "begeniyorum", "Begeniyorum");
        SurveyQuestionOption aliBegenmiyorumOption = buildOption(favorabilityAli, 2, "begenmiyorum", "Begenmiyorum");

        questionOptionsByQuestionId.put(familiarityLevent.getId(), List.of(leventTaniyorumOption, leventTanimiyorumOption, leventHicDuymadimOption));
        questionOptionsByQuestionId.put(familiarityAli.getId(), List.of(aliTaniyorumOption, aliTanimiyorumOption, aliHicDuymadimOption));
        questionOptionsByQuestionId.put(favorabilityLevent.getId(), List.of(leventBegeniyorumOption, leventBegenmiyorumOption));
        questionOptionsByQuestionId.put(favorabilityAli.getId(), List.of(aliBegeniyorumOption, aliBegenmiyorumOption));
        questionOptionsByQuestionId.put(fixture.openQuestion.getId(), List.of());

        InterviewOrchestrationResponse startResponse = service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));
        assertThat(startResponse.question()).isNotNull();
        assertThat(startResponse.question().code()).isEqualTo("b2_levent");

        InterviewOrchestrationResponse afterLevent = service.submitAnswer(
                new InterviewAnswerRequest(fixture.callAttempt.getId(), null, null, "Taniyorum", InterviewConversationSignal.ANSWER)
        );
        assertThat(afterLevent.question()).isNotNull();
        assertThat(afterLevent.question().code()).isEqualTo("b2_ali");

        InterviewOrchestrationResponse afterAli = service.submitAnswer(
                new InterviewAnswerRequest(fixture.callAttempt.getId(), null, null, "Hic duymadim", InterviewConversationSignal.ANSWER)
        );
        assertThat(afterAli.question()).isNotNull();
        assertThat(afterAli.question().code()).isEqualTo("b3_levent");
        assertThat(afterAli.totalQuestionCount()).isEqualTo(4);

        InterviewOrchestrationResponse afterLeventFavorability = service.submitAnswer(
                new InterviewAnswerRequest(fixture.callAttempt.getId(), null, null, "Begeniyorum", InterviewConversationSignal.ANSWER)
        );
        assertThat(afterLeventFavorability.question()).isNotNull();
        assertThat(afterLeventFavorability.question().code()).isEqualTo("why");
    }

    @Test
    void submitAnswer_onlyAsksAliFavorabilityWhenAliKnownAndLeventUnknown() {
        SurveyQuestion familiarityLevent = buildQuestion("b2_levent", 1, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece taniyorsunuz?");
        familiarityLevent.setSettingsJson("""
                {"groupCode":"B2","rowCode":"levent_uysal"}
                """);
        SurveyQuestion familiarityAli = buildQuestion("b2_ali", 2, QuestionType.SINGLE_CHOICE, "Ali Mahir Basarir'i ne derece taniyorsunuz?");
        familiarityAli.setSettingsJson("""
                {"groupCode":"B2","rowCode":"ali_mahir_basarir"}
                """);

        SurveyQuestion favorabilityLevent = buildQuestion("b3_levent", 3, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece begeniyorsunuz?");
        favorabilityLevent.setSettingsJson("""
                {"groupCode":"B3","rowCode":"levent_uysal"}
                """);
        favorabilityLevent.setBranchConditionJson("""
                {
                  "askIf": {
                    "groupCode": "B2",
                    "sameRowCode": true,
                    "answerTagsAnyOf": ["knowledge_positive"]
                  }
                }
                """);
        SurveyQuestion favorabilityAli = buildQuestion("b3_ali", 4, QuestionType.SINGLE_CHOICE, "Ali Mahir Basarir'i ne derece begeniyorsunuz?");
        favorabilityAli.setSettingsJson("""
                {"groupCode":"B3","rowCode":"ali_mahir_basarir"}
                """);
        favorabilityAli.setBranchConditionJson(favorabilityLevent.getBranchConditionJson());

        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(familiarityLevent, familiarityAli, favorabilityLevent, favorabilityAli, fixture.openQuestion));

        SurveyQuestionOption leventTaniyorumOption = buildOption(familiarityLevent, 1, "taniyorum", "Taniyorum");
        SurveyQuestionOption leventTanimiyorumOption = buildOption(familiarityLevent, 2, "tanimiyorum", "Tanimiyorum");
        SurveyQuestionOption leventHicDuymadimOption = buildOption(familiarityLevent, 3, "hic_duymadim", "Hic duymadim");
        SurveyQuestionOption aliTaniyorumOption = buildOption(familiarityAli, 1, "taniyorum", "Taniyorum");
        SurveyQuestionOption aliTanimiyorumOption = buildOption(familiarityAli, 2, "tanimiyorum", "Tanimiyorum");
        SurveyQuestionOption aliHicDuymadimOption = buildOption(familiarityAli, 3, "hic_duymadim", "Hic duymadim");
        SurveyQuestionOption leventBegeniyorumOption = buildOption(favorabilityLevent, 1, "begeniyorum", "Begeniyorum");
        SurveyQuestionOption leventBegenmiyorumOption = buildOption(favorabilityLevent, 2, "begenmiyorum", "Begenmiyorum");
        SurveyQuestionOption aliBegeniyorumOption = buildOption(favorabilityAli, 1, "begeniyorum", "Begeniyorum");
        SurveyQuestionOption aliBegenmiyorumOption = buildOption(favorabilityAli, 2, "begenmiyorum", "Begenmiyorum");

        questionOptionsByQuestionId.put(familiarityLevent.getId(), List.of(leventTaniyorumOption, leventTanimiyorumOption, leventHicDuymadimOption));
        questionOptionsByQuestionId.put(familiarityAli.getId(), List.of(aliTaniyorumOption, aliTanimiyorumOption, aliHicDuymadimOption));
        questionOptionsByQuestionId.put(favorabilityLevent.getId(), List.of(leventBegeniyorumOption, leventBegenmiyorumOption));
        questionOptionsByQuestionId.put(favorabilityAli.getId(), List.of(aliBegeniyorumOption, aliBegenmiyorumOption));
        questionOptionsByQuestionId.put(fixture.openQuestion.getId(), List.of());

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse afterLevent = service.submitAnswer(
                new InterviewAnswerRequest(fixture.callAttempt.getId(), null, null, "Hic duymadim", InterviewConversationSignal.ANSWER)
        );
        assertThat(afterLevent.question()).isNotNull();
        assertThat(afterLevent.question().code()).isEqualTo("b2_ali");

        InterviewOrchestrationResponse afterAli = service.submitAnswer(
                new InterviewAnswerRequest(fixture.callAttempt.getId(), null, null, "Taniyorum", InterviewConversationSignal.ANSWER)
        );
        assertThat(afterAli.question()).isNotNull();
        assertThat(afterAli.question().code()).isEqualTo("b3_ali");
        assertThat(afterAli.totalQuestionCount()).isEqualTo(4);
    }

    @Test
    void submitAnswer_skipsWholeFavorabilityGroupWhenNobodyIsKnown() {
        SurveyQuestion familiarityLevent = buildQuestion("b2_levent", 1, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece taniyorsunuz?");
        familiarityLevent.setSettingsJson("""
                {"groupCode":"B2","rowCode":"levent_uysal"}
                """);
        SurveyQuestion familiarityAli = buildQuestion("b2_ali", 2, QuestionType.SINGLE_CHOICE, "Ali Mahir Basarir'i ne derece taniyorsunuz?");
        familiarityAli.setSettingsJson("""
                {"groupCode":"B2","rowCode":"ali_mahir_basarir"}
                """);

        SurveyQuestion favorabilityLevent = buildQuestion("b3_levent", 3, QuestionType.SINGLE_CHOICE, "Levent Uysal'i ne derece begeniyorsunuz?");
        favorabilityLevent.setSettingsJson("""
                {"groupCode":"B3","rowCode":"levent_uysal"}
                """);
        favorabilityLevent.setBranchConditionJson("""
                {
                  "askIf": {
                    "groupCode": "B2",
                    "sameRowCode": true,
                    "answerTagsAnyOf": ["knowledge_positive"]
                  }
                }
                """);
        SurveyQuestion favorabilityAli = buildQuestion("b3_ali", 4, QuestionType.SINGLE_CHOICE, "Ali Mahir Basarir'i ne derece begeniyorsunuz?");
        favorabilityAli.setSettingsJson("""
                {"groupCode":"B3","rowCode":"ali_mahir_basarir"}
                """);
        favorabilityAli.setBranchConditionJson(favorabilityLevent.getBranchConditionJson());

        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(familiarityLevent, familiarityAli, favorabilityLevent, favorabilityAli, fixture.openQuestion));

        SurveyQuestionOption leventTaniyorumOption = buildOption(familiarityLevent, 1, "taniyorum", "Taniyorum");
        SurveyQuestionOption leventTanimiyorumOption = buildOption(familiarityLevent, 2, "tanimiyorum", "Tanimiyorum");
        SurveyQuestionOption leventHicDuymadimOption = buildOption(familiarityLevent, 3, "hic_duymadim", "Hic duymadim");
        SurveyQuestionOption aliTaniyorumOption = buildOption(familiarityAli, 1, "taniyorum", "Taniyorum");
        SurveyQuestionOption aliTanimiyorumOption = buildOption(familiarityAli, 2, "tanimiyorum", "Tanimiyorum");
        SurveyQuestionOption aliHicDuymadimOption = buildOption(familiarityAli, 3, "hic_duymadim", "Hic duymadim");
        SurveyQuestionOption leventBegeniyorumOption = buildOption(favorabilityLevent, 1, "begeniyorum", "Begeniyorum");
        SurveyQuestionOption leventBegenmiyorumOption = buildOption(favorabilityLevent, 2, "begenmiyorum", "Begenmiyorum");
        SurveyQuestionOption aliBegeniyorumOption = buildOption(favorabilityAli, 1, "begeniyorum", "Begeniyorum");
        SurveyQuestionOption aliBegenmiyorumOption = buildOption(favorabilityAli, 2, "begenmiyorum", "Begenmiyorum");

        questionOptionsByQuestionId.put(familiarityLevent.getId(), List.of(leventTaniyorumOption, leventTanimiyorumOption, leventHicDuymadimOption));
        questionOptionsByQuestionId.put(familiarityAli.getId(), List.of(aliTaniyorumOption, aliTanimiyorumOption, aliHicDuymadimOption));
        questionOptionsByQuestionId.put(favorabilityLevent.getId(), List.of(leventBegeniyorumOption, leventBegenmiyorumOption));
        questionOptionsByQuestionId.put(favorabilityAli.getId(), List.of(aliBegeniyorumOption, aliBegenmiyorumOption));
        questionOptionsByQuestionId.put(fixture.openQuestion.getId(), List.of());

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(
                new InterviewAnswerRequest(fixture.callAttempt.getId(), null, null, "Hic duymadim", InterviewConversationSignal.ANSWER)
        );
        InterviewOrchestrationResponse afterAli = service.submitAnswer(
                new InterviewAnswerRequest(fixture.callAttempt.getId(), null, null, "Tanimiyorum", InterviewConversationSignal.ANSWER)
        );

        assertThat(afterAli.question()).isNotNull();
        assertThat(afterAli.question().code()).isEqualTo("why");
        assertThat(afterAli.totalQuestionCount()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Öğretmenim | Öğretmen",
            "Ben emekliyim | Emekli",
            "Ev hanımıyım | Ev hanımı",
            "Doktor | Doktor",
            // Not in the known list, but still an occupation: kept as said.
            "Veterinerim | Veterinerim",
            "Kasiyer olarak çalışıyorum | Kasiyer olarak çalışıyorum"
    })
    void submitAnswer_acceptsAnyOccupationAndCleansUpKnownOnes(String utterance, String expectedText) {
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.openQuestion));
        fixture.openQuestion.setCode("occupation");
        fixture.openQuestion.setTitle("Mesleğiniz nedir?");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, utterance, InterviewConversationSignal.ANSWER));

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        SurveyAnswer answer = answersByResponseId.get(savedResponse.getId()).getFirst();
        assertThat(answer.isValid()).isTrue();
        assertThat(answer.getAnswerText()).isEqualTo(expectedText);
    }

    @Test
    void submitAnswer_doesNotTreatOpenQuestionsAsOccupationBecauseOfTheSurveyName() {
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.openQuestion));
        fixture.survey.setName("Meslek ve Yaşam Memnuniyeti");
        fixture.openQuestion.setCode("district_problem");
        fixture.openQuestion.setTitle("İlçenizin en önemli sorunu nedir?");
        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        service.submitAnswer(new InterviewAnswerRequest(
                fixture.callAttempt.getId(), null, null, "Trafik ve altyapı", InterviewConversationSignal.ANSWER));

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        SurveyAnswer answer = answersByResponseId.get(savedResponse.getId()).getFirst();
        assertThat(answer.isValid()).isTrue();
        assertThat(answer.getAnswerText()).isEqualTo("Trafik ve altyapı");
    }

    @Test
    void submitAnswer_normalizesCivicOpenEndedAsrPhraseBeforeSaving() {
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.openQuestion));
        fixture.openQuestion.setRequired(true);
        fixture.openQuestion.setTitle("Milletvekillerinden Izmir icin en oncelikli beklentiniz nedir?");

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Sehzadin sorunlariyla ilgilenmelerini bekliyorum.",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getRawInputText()).isEqualTo("Sehzadin sorunlariyla ilgilenmelerini bekliyorum.");
        assertThat(answers.getFirst().getAnswerText()).isEqualTo("şehrin sorunlarıyla ilgilenmelerini bekliyorum.");
        assertThat(response.question()).isNull();
    }

    @Test
    void submitAnswer_usesOperationScopedAutoEntityLexiconWhenQuestionSettingsDoNotContainIt() {
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.openQuestion));
        fixture.openQuestion.setRequired(true);
        fixture.openQuestion.setTitle("Desteklediginiz adayi soyler misiniz?");
        fixture.openQuestion.setSettingsJson("{}");
        runtimeEntries = List.of(new OperationAutoEntityLexiconService.NamedEntityEntry(
                "Cemil Tugay",
                List.of("Cemil Tugay", "Tugay")
        ));

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Tugay",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isTrue();
        assertThat(answers.getFirst().getAnswerText()).isEqualTo("Cemil Tugay");
        assertThat(response.question()).isNull();
    }

    @Test
    void submitAnswer_requestsRepeatForSuspiciousCivicOpenEndedPhraseWhenCorrectionIsNotSafe() {
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(fixture.survey.getId()))
                .thenReturn(List.of(fixture.openQuestion));
        fixture.openQuestion.setRequired(true);
        fixture.openQuestion.setTitle("Milletvekillerinden Izmir icin en oncelikli beklentiniz nedir?");

        service.startInterview(new InterviewSessionRequest(fixture.callAttempt.getId(), null, null));

        InterviewOrchestrationResponse response = service.submitAnswer(
                new InterviewAnswerRequest(
                        fixture.callAttempt.getId(),
                        null,
                        null,
                        "Sehzade konusunda bir sey demek istiyorum.",
                        InterviewConversationSignal.ANSWER
                )
        );

        SurveyResponse savedResponse = responsesByAttemptId.get(fixture.callAttempt.getId());
        List<SurveyAnswer> answers = answersByResponseId.get(savedResponse.getId());

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().isValid()).isFalse();
        assertThat(answers.getFirst().getRetryCount()).isEqualTo(1);
        assertThat(response.question()).isNotNull();
        assertThat(response.prompt()).contains("Could you please repeat your answer briefly and clearly?");
    }

    private SurveyQuestion buildQuestion(String code, int order, QuestionType type, String title) {
        SurveyQuestion question = new SurveyQuestion();
        question.setId(UUID.randomUUID());
        question.setCompany(fixture.company);
        question.setSurvey(fixture.survey);
        question.setCode(code);
        question.setQuestionOrder(order);
        question.setQuestionType(type);
        question.setTitle(title);
        question.setRequired(true);
        question.setSettingsJson("{}");
        question.setBranchConditionJson("{}");
        return question;
    }

    private int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return 0;
        }

        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count += 1;
            index += needle.length();
        }
        return count;
    }

    private SurveyQuestionOption buildOption(SurveyQuestion question, int order, String optionCode, String label) {
        SurveyQuestionOption option = new SurveyQuestionOption();
        option.setId(UUID.randomUUID());
        option.setCompany(fixture.company);
        option.setSurveyQuestion(question);
        option.setOptionOrder(order);
        option.setOptionCode(optionCode);
        option.setLabel(label);
        option.setValue(optionCode);
        option.setActive(true);
        return option;
    }

    private static final class Fixture {
        private final Company company = new Company();
        private final Survey survey = new Survey();
        private final Operation operation = new Operation();
        private final OperationContact contact = new OperationContact();
        private final CallJob callJob = new CallJob();
        private final CallAttempt callAttempt = new CallAttempt();
        private final SurveyQuestion yesNoQuestion = new SurveyQuestion();
        private final SurveyQuestion openQuestion = new SurveyQuestion();
        private final SurveyQuestion ratingQuestion = new SurveyQuestion();
        private final SurveyQuestionOption yesOption = new SurveyQuestionOption();
        private final SurveyQuestionOption noOption = new SurveyQuestionOption();

        private Fixture() {
            company.setId(UUID.randomUUID());

            survey.setId(UUID.randomUUID());
            survey.setCompany(company);
            survey.setName("CSAT");
            survey.setMaxRetryPerQuestion(2);

            operation.setId(UUID.randomUUID());
            operation.setCompany(company);
            operation.setSurvey(survey);
            operation.setName("April CSAT Wave");

            contact.setId(UUID.randomUUID());
            contact.setCompany(company);
            contact.setOperation(operation);
            contact.setFirstName("Aylin");
            contact.setLastName("Yilmaz");
            contact.setPhoneNumber("905551112233");

            callJob.setId(UUID.randomUUID());
            callJob.setCompany(company);
            callJob.setOperation(operation);
            callJob.setOperationContact(contact);
            callJob.setStatus(CallJobStatus.IN_PROGRESS);
            callJob.setScheduledFor(OffsetDateTime.now());
            callJob.setAvailableAt(OffsetDateTime.now());
            callJob.setIdempotencyKey("op:contact");

            callAttempt.setId(UUID.randomUUID());
            callAttempt.setCompany(company);
            callAttempt.setCallJob(callJob);
            callAttempt.setOperation(operation);
            callAttempt.setOperationContact(contact);
            callAttempt.setAttemptNumber(1);
            callAttempt.setProvider(CallProvider.ELEVENLABS);
            callAttempt.setStatus(CallAttemptStatus.IN_PROGRESS);
            callAttempt.setRawProviderPayload("{}");

            yesNoQuestion.setId(UUID.randomUUID());
            yesNoQuestion.setCompany(company);
            yesNoQuestion.setSurvey(survey);
            yesNoQuestion.setCode("satisfied");
            yesNoQuestion.setQuestionOrder(1);
            yesNoQuestion.setQuestionType(QuestionType.SINGLE_CHOICE);
            yesNoQuestion.setTitle("Memnun musunuz?");
            yesNoQuestion.setRequired(true);
            yesNoQuestion.setSettingsJson("{}");
            yesNoQuestion.setBranchConditionJson("{}");

            openQuestion.setId(UUID.randomUUID());
            openQuestion.setCompany(company);
            openQuestion.setSurvey(survey);
            openQuestion.setCode("why");
            openQuestion.setQuestionOrder(2);
            openQuestion.setQuestionType(QuestionType.OPEN_ENDED);
            openQuestion.setTitle("Neden?");
            openQuestion.setRequired(false);
            openQuestion.setSettingsJson("{}");
            openQuestion.setBranchConditionJson("{}");

            ratingQuestion.setId(UUID.randomUUID());
            ratingQuestion.setCompany(company);
            ratingQuestion.setSurvey(survey);
            ratingQuestion.setCode("score");
            ratingQuestion.setQuestionOrder(1);
            ratingQuestion.setQuestionType(QuestionType.RATING);
            ratingQuestion.setTitle("Bizi puanlar mısınız?");
            ratingQuestion.setRequired(true);
            ratingQuestion.setSettingsJson("{}");
            ratingQuestion.setBranchConditionJson("{}");

            yesOption.setId(UUID.randomUUID());
            yesOption.setCompany(company);
            yesOption.setSurveyQuestion(yesNoQuestion);
            yesOption.setOptionOrder(1);
            yesOption.setOptionCode("yes");
            yesOption.setLabel("Evet");
            yesOption.setValue("yes");
            yesOption.setActive(true);

            noOption.setId(UUID.randomUUID());
            noOption.setCompany(company);
            noOption.setSurveyQuestion(yesNoQuestion);
            noOption.setOptionOrder(2);
            noOption.setOptionCode("no");
            noOption.setLabel("Hayır");
            noOption.setValue("no");
            noOption.setActive(true);
        }
    }
}
