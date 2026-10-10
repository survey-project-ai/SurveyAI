package com.yourcompany.surveyai.call.application.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.dto.request.InterviewAnswerRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewConversationSignal;
import com.yourcompany.surveyai.call.application.dto.request.InterviewFinishRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewSessionRequest;
import com.yourcompany.surveyai.call.application.dto.response.InterviewOrchestrationResponse;
import com.yourcompany.surveyai.call.application.support.SpokenNumberParser;
import com.yourcompany.surveyai.call.application.dto.response.InterviewQuestionOptionPayload;
import com.yourcompany.surveyai.call.application.dto.response.InterviewQuestionPayload;
import com.yourcompany.surveyai.call.application.service.CallInterviewOrchestrationService;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.operation.application.support.OperationAutoEntityLexiconService;
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
import com.yourcompany.surveyai.operation.support.OperationContactPhoneResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CallInterviewOrchestrationServiceImpl implements CallInterviewOrchestrationService {

    private static final Pattern TRANSCRIPT_MARKUP_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern VOICE_DIRECTION_PATTERN = Pattern.compile("\\[(?:[a-zA-Z_\\-]{2,20})]|\\((?:[a-zA-Z_\\-]{2,20})\\)");
    private static final Set<String> SEMANTIC_FILLER_TOKENS = Set.of(
            "evet", "hayir", "onu", "bunu", "bana", "gibi", "yani", "sanirim", "galiba", "biraz", "pek", "cok", "fazla",
            "eee", "ee", "hmm", "hmmm", "he", "she", "him", "her", "it", "that", "this", "ya", "ve", "ama", "fakat"
    );
    private static final String CONSENT_STATE_KEY = "openingConsentState";
    private static final String CONSENT_PROMPT_DELIVERED_KEY = "openingConsentPromptDelivered";
    private static final String GREETING_RECEIVED_KEY = "openingGreetingReceived";
    private static final String LAST_ANSWER_SUBMISSION_KEY = "lastAnswerSubmission";
    private static final Duration REPEATED_SUBMISSION_WINDOW = Duration.ofSeconds(3);
    private static final Logger log = LoggerFactory.getLogger(CallInterviewOrchestrationServiceImpl.class);
    private static final String CONSENT_PENDING = "PENDING";
    private static final String CONSENT_GRANTED = "GRANTED";
    private static final String CONSENT_DECLINED = "DECLINED";
    // No clear yes or no after the consent question was asked again as often as a survey question is retried.
    private static final String CONSENT_UNCLEAR = "UNCLEAR";
    private static final String CONSENT_CLARIFICATION_COUNT_KEY = "openingConsentClarificationCount";
    private static final String ANSWER_PROCESSING_FAILED_REASON = "Answer could not be processed";
    private static final List<String> CONSENT_DECLINE_PHRASES = List.of(
            "katilmam", "katilmak istemiyorum", "katilmak istemem", "katilmayacagim",
            "istemiyorum", "istemem", "ilgilenmiyorum", "ilgilenmem",
            "uygun degil", "uygun degilim", "musait degil", "musait degilim", "mesgulum",
            "simdi olmaz", "olmaz", "zamanim yok", "vaktim yok",
            "rahatsiz etmeyin", "aramayin", "beni aramayin",
            "not now", "not interested", "no thanks", "no thank you", "do not call", "dont call"
    );
    private static final List<String> CONSENT_ACCEPT_PHRASES = List.of(
            "sorabilirsiniz", "sorabilirsin", "buyurun", "buyrun", "dinliyorum", "olur", "uygunum", "musaitim",
            "sorun degil", "sorun yok", "tabii", "tabi", "elbette", "tamam", "peki", "neden olmasin",
            "go ahead", "you can ask", "sure", "ok", "okay"
    );
    private static final Map<String, Integer> RATING_SEMANTIC_TR_1_5 = Map.ofEntries(
            Map.entry("hic onemli degil", 1),
            Map.entry("onemsiz", 1),
            Map.entry("pek onemli degil", 2),
            Map.entry("az onemli", 2),
            Map.entry("orta", 3),
            Map.entry("ne onemli ne onemsiz", 3),
            Map.entry("kararsizim", 3),
            Map.entry("onemli", 4),
            Map.entry("oldukca onemli", 5),
            Map.entry("cok onemli", 5),
            Map.entry("asiri onemli", 5)
    );
    private static final Map<String, Integer> RATING_SEMANTIC_EN_1_5 = Map.ofEntries(
            Map.entry("not important at all", 1),
            Map.entry("unimportant", 1),
            Map.entry("slightly important", 2),
            Map.entry("not very important", 2),
            Map.entry("neutral", 3),
            Map.entry("moderate", 3),
            Map.entry("important", 4),
            Map.entry("quite important", 5),
            Map.entry("very important", 5),
            Map.entry("extremely important", 5)
    );
    private static final Set<String> YES_WORDS = Set.of("evet", "olur", "tabii", "tabi", "yes", "yeah", "yep", "dogru", "buyurun", "sorabilirsiniz", "dinliyorum", "uygun");
    private static final Set<String> NO_WORDS = Set.of("hayir", "yok", "istemiyorum", "no", "nope", "degil", "uygun degil", "mesgul", "musait degil");
    private static final Set<String> EXPLICIT_YES_WORDS = Set.of("evet", "yes", "yeah", "yep");
    private static final Set<String> EXPLICIT_NO_WORDS = Set.of("hayir", "yok", "istemiyorum", "no", "nope");
    private static final Set<String> CIVIC_OPEN_ENDED_HINTS = Set.of(
            "sehir", "kent", "izmir", "belediye", "milletvekili", "milletvekillerinden",
            "sorun", "sorunlari", "beklenti", "oncelik", "oncelikli", "ulasim", "trafik", "altyapi", "hizmet"
    );
    private static final Set<String> TRANSPORT_OPEN_ENDED_HINTS = Set.of(
            "ulasim", "trafik", "yol", "metro", "otobus", "altyapi", "toplu tasima"
    );
    private static final Set<String> SUSPICIOUS_CIVIC_ASR_TOKENS = Set.of(
            "sehzadin", "sehradin", "sehzade", "şehirin", "sehirin"
    );
    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    // The "I am" ending of a Turkish noun: ogretmen-im, emekli-yim, memur-um, sofor-um.
    private static final Pattern PERSONAL_ENDING = Pattern.compile("y?[ıiuü]m$");
    private static final Set<String> OCCUPATION_OPEN_ENDED_HINTS = Set.of(
            "meslek", "mesleginiz", "ne is yapiyorsunuz", "calisma durumu",
            "is durumu", "is hayati", "meslegi", "meslegini", "occupation", "job"
    );
    // Normalized (via normalize()) karşılıkları — Turkish diacritics stripped
    private static final Set<String> KNOWN_OCCUPATIONS_NORMALIZED = Set.of(
            // İstihdam durumu
            "ev hanimi", "ev kadini", "ev yoneticisi",
            "emekli", "emekliyim",
            "ogrenci", "ogrenciyim",
            "issiz", "issizim", "is ariyorum", "is ariyor",
            "calisiyor", "calisiyorum", "calismiyor",
            // Kamu / Devlet
            "memur", "devlet memuru", "kamu calisani",
            "ogretmen", "ogretim uyesi", "ogretim gorevlisi", "akademisyen",
            "polis", "emniyet memuru", "jandarma",
            "asker", "subay",
            "hakim", "savci",
            // Sağlık
            "doktor", "hekim", "pratisyen hekim", "uzman doktor",
            "hemsire",
            "eczaci",
            "dis hekimi",
            "fizyoterapist",
            "psikolog", "psikyatrist",
            "diyetisyen",
            "saglik calisani",
            // Mühendislik / Teknik
            "muhendis", "insaat muhendisi", "makine muhendisi",
            "elektrik muhendisi", "bilgisayar muhendisi",
            "mimar",
            "teknisyen", "tekniker",
            "elektrikci",
            "tesisatci",
            "yazilimci", "programci", "yazilim gelistirici",
            // Hukuk / Finans
            "avukat", "noter",
            "muhasebeci", "mali musavir",
            "ekonomist",
            "banker", "banka calisani",
            // Ticaret / Yönetim
            "esnaf", "dukkan sahibi",
            "isci", "fabrika iscisi",
            "is insani", "girisimci", "isletmeci",
            "mudur", "yonetici", "genel mudur",
            "sekreter",
            "uzman", "danisman",
            "pazarlamaci", "satis temsilcisi",
            // Ziraat
            "ciftci", "tarimci",
            // Sanat / Medya
            "gazeteci", "muhabir",
            "sanatci", "ressam", "muzisyen",
            // Ulaşım / Hizmet
            "sofor", "surucu", "taksici",
            "pilot",
            "berber", "kuafor",
            "kasap", "manav",
            "terzi",
            "tamirci", "marangoz",
            "boyaci",
            "temizlikci", "temizlik gorevlisi",
            "garson", "asci", "pastaci",
            "guvenlik gorevlisi",
            // Serbest
            "serbest meslek", "serbest calisiyor", "kendi hesabima",
            "emekli isci", "emekli memur",
            "ev hizmetcisi", "gundelikci"
    );
    // Checked in declaration order. Map.ofEntries has no stable iteration order, which made the
    // matched special answer differ between JVM runs.
    private static final Map<String, List<String>> DEFAULT_SPECIAL_ANSWERS = orderedSpecialAnswers(
            Map.entry("bilmiyorum", List.of(
                    "bilmiyorum",
                    "fikrim yok",
                    "emin değilim",
                    "hatırlamıyorum",
                    "hatırlamıyorum",
                    "no idea",
                    "not sure"
            )),
            Map.entry("cevap_yok", List.of(
                    "cevap yok",
                    "boş geç",
                    "boş geçeyim",
                    "pas",
                    "pas geçiyorum",
                    "pas geç",
                    "geçelim",
                    "skip"
            )),
            Map.entry("reddetti", List.of(
                    "cevap vermek istemiyorum",
                    "söylemek istemiyorum",
                    "paylaşmak istemiyorum",
                    "buna cevap vermeyeyim",
                    "geçmek istiyorum",
                    "istemiyorum"
            ))
    );

    private final CallAttemptRepository callAttemptRepository;
    private final SurveyResponseRepository surveyResponseRepository;
    private final SurveyAnswerRepository surveyAnswerRepository;
    private final SurveyQuestionRepository surveyQuestionRepository;
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository;
    private final ObjectMapper objectMapper;
    private final TurkeyGeoDataService turkeyGeoDataService;
    private final OperationAutoEntityLexiconService operationAutoEntityLexiconService;

    public CallInterviewOrchestrationServiceImpl(
            CallAttemptRepository callAttemptRepository,
            SurveyResponseRepository surveyResponseRepository,
            SurveyAnswerRepository surveyAnswerRepository,
            SurveyQuestionRepository surveyQuestionRepository,
            SurveyQuestionOptionRepository surveyQuestionOptionRepository,
            ObjectMapper objectMapper,
            TurkeyGeoDataService turkeyGeoDataService,
            OperationAutoEntityLexiconService operationAutoEntityLexiconService
    ) {
        this.callAttemptRepository = callAttemptRepository;
        this.surveyResponseRepository = surveyResponseRepository;
        this.surveyAnswerRepository = surveyAnswerRepository;
        this.surveyQuestionRepository = surveyQuestionRepository;
        this.surveyQuestionOptionRepository = surveyQuestionOptionRepository;
        this.objectMapper = objectMapper;
        this.turkeyGeoDataService = turkeyGeoDataService;
        this.operationAutoEntityLexiconService = operationAutoEntityLexiconService;
    }

    @Override
    public InterviewOrchestrationResponse startInterview(InterviewSessionRequest request) {
        SessionContext context = loadSessionContext(request.callAttemptId(), request.providerCallId());
        SurveyResponse response = ensureSurveyResponse(context.callAttempt());
        initializeOpeningConsentState(response, context.survey());
        // A repeated start must not reopen an interview that has already ended (finished, stopped or declined).
        if (isTerminalResponse(response)) {
            surveyResponseRepository.save(response);
            return buildTerminalResponse(context, response, resolveTerminalClosingPrompt(context, response));
        }
        response.setStatus(SurveyResponseStatus.PARTIAL);
        surveyResponseRepository.save(response);
        if (requiresOpeningGreeting(context.survey()) && !isOpeningGreetingReceived(response)) {
            return buildConsentPhaseResponse(context, response, null);
        }
        if (isOpeningConsentPending(context.survey(), response)) {
            return buildConsentPhaseResponse(context, response, null);
        }
        return buildProgressResponse(context, response, buildOpeningLeadIn(context), resolveFlowState(context));
    }

    @Override
    public InterviewOrchestrationResponse getCurrentQuestion(InterviewSessionRequest request) {
        SessionContext context = loadSessionContext(request.callAttemptId(), request.providerCallId());
        SurveyResponse response = ensureSurveyResponse(context.callAttempt());
        initializeOpeningConsentState(response, context.survey());
        return buildCurrentStepResponse(context, response);
    }

    private InterviewOrchestrationResponse buildCurrentStepResponse(SessionContext context, SurveyResponse response) {
        if (isTerminalResponse(response)) {
            surveyResponseRepository.save(response);
            return buildTerminalResponse(context, response, resolveTerminalClosingPrompt(context, response));
        }
        // The agent asked what to say before the callee's greeting reached the backend. The opening
        // returned here gets spoken, so it is recorded as delivered: the callee's next words are then
        // read as the reply to it, instead of as the greeting, which would repeat the opening and drop them.
        if (requiresOpeningGreeting(context.survey()) && !isOpeningGreetingReceived(response)) {
            InterviewOrchestrationResponse openingResponse = deliverOpening(context, response);
            surveyResponseRepository.save(response);
            return openingResponse;
        }
        if (isOpeningConsentPending(context.survey(), response)) {
            updateOpeningConsentPromptDelivered(response, true);
            surveyResponseRepository.save(response);
            return buildConsentPhaseResponse(context, response, buildOpeningConsentRepeatPrompt(context.survey()));
        }
        surveyResponseRepository.save(response);
        return buildProgressResponse(context, response, null, resolveFlowState(context));
    }

    @Override
    public InterviewOrchestrationResponse submitAnswer(InterviewAnswerRequest request) {
        SessionContext context = loadSessionContext(request.callAttemptId(), request.providerCallId());
        SurveyResponse response = ensureSurveyResponse(context.callAttempt());
        initializeOpeningConsentState(response, context.survey());
        if (isTerminalResponse(response)) {
            surveyResponseRepository.save(response);
            return buildTerminalResponse(context, response, resolveTerminalClosingPrompt(context, response));
        }

        if (request.signal() == InterviewConversationSignal.STOP_REQUEST) {
            FlowState flowState = resolveFlowState(context);
            response.setStatus(SurveyResponseStatus.ABANDONED);
            response.setCompletedAt(OffsetDateTime.now());
            updateCompletionPercent(response, context, flowState.eligibleQuestions());
            surveyResponseRepository.save(response);
            return buildTerminalResponse(context, response, buildClosingPrompt(context.survey()), flowState);
        }

        if (requiresOpeningGreeting(context.survey()) && !isOpeningGreetingReceived(response)) {
            InterviewOrchestrationResponse greetingResponse = handleOpeningGreeting(context, response, request);
            surveyResponseRepository.save(response);
            return greetingResponse;
        }

        if (isOpeningConsentPending(context.survey(), response)) {
            InterviewOrchestrationResponse consentResponse = handleOpeningConsent(context, response, request);
            surveyResponseRepository.save(response);
            return consentResponse;
        }

        FlowState currentFlowState = resolveFlowState(context);
        QuestionCursor cursor = currentFlowState.cursor();
        if (cursor.question() == null) {
            finalizeResponse(response, context, null, currentFlowState);
            surveyResponseRepository.save(response);
            return buildTerminalResponse(context, response, buildClosingPrompt(context.survey()), currentFlowState);
        }

        if (request.signal() == InterviewConversationSignal.IDENTITY_REQUEST) {
            return buildProgressResponse(
                    context,
                    response,
                    buildInSurveyIdentityPrompt(context),
                    currentFlowState
            );
        }

        if (request.signal() == InterviewConversationSignal.REPEAT_REQUEST) {
            return buildProgressResponse(context, response, localized(context.survey(),
                    "Soruyu yavaşça bir kez daha sor ve henüz ilerleme.",
                    "Repeat the same question slowly and do not move forward yet."), currentFlowState);
        }

        if (request.signal() == InterviewConversationSignal.IDENTITY_REQUEST) {
            return buildProgressResponse(
                    context,
                    response,
                    localized(context.survey(),
                            "Kısa bir şekilde kim olduğunu ve neden aradığını açıkla, sonra aynı soruyu tekrar sor.",
                            "Briefly explain who you are, mention the survey and operation, then ask the same question again."),
                    currentFlowState
            );
        }

        if (isStaleOrRepeatedSubmission(request, response, cursor.question())) {
            log.warn(
                    "Ignoring a stale or repeated answer submission. callAttemptId={} currentQuestionId={} requestQuestionId={}",
                    context.callAttempt().getId(),
                    cursor.question().getId(),
                    request.questionId()
            );
            return buildProgressResponse(context, response, null, currentFlowState);
        }

        SurveyAnswer answer = context.answersByQuestionId().get(cursor.question().getId());
        if (answer == null) {
            answer = createSurveyAnswer(response, cursor.question());
        }
        NormalizedAnswer normalizedAnswer = normalizeAnswer(
                context,
                cursor.question(),
                context.optionsByQuestionId().getOrDefault(cursor.question().getId(), List.of()),
                request,
                answer
        );

        return applyAnswerAndContinue(context, response, cursor, answer, normalizedAnswer, request.utteranceText());
    }

    // submitAnswer failed and its transaction was rolled back. The attempt counts as an unclear answer to
    // the current question: the agent apologizes and asks it again, and a failure that keeps coming back
    // moves the interview on once the retries are used up, instead of repeating the same question until
    // the callee hangs up.
    @Override
    public InterviewOrchestrationResponse recoverFailedAnswer(InterviewAnswerRequest request) {
        SessionContext context = loadSessionContext(request.callAttemptId(), request.providerCallId());
        SurveyResponse response = ensureSurveyResponse(context.callAttempt());
        initializeOpeningConsentState(response, context.survey());
        boolean answeringQuestions = !isTerminalResponse(response)
                && !(requiresOpeningGreeting(context.survey()) && !isOpeningGreetingReceived(response))
                && !isOpeningConsentPending(context.survey(), response);
        boolean answerSignal = request.signal() == null
                || request.signal() == InterviewConversationSignal.ANSWER
                || request.signal() == InterviewConversationSignal.NO_INPUT;
        QuestionCursor cursor = answeringQuestions ? resolveFlowState(context).cursor() : null;
        if (!answerSignal || cursor == null || cursor.question() == null) {
            return buildCurrentStepResponse(context, response);
        }

        SurveyAnswer answer = context.answersByQuestionId().get(cursor.question().getId());
        if (answer == null) {
            answer = createSurveyAnswer(response, cursor.question());
        }
        NormalizedAnswer failedAnswer = NormalizedAnswer.invalidWithClarification(
                ANSWER_PROCESSING_FAILED_REASON,
                trimToNull(request.utteranceText()),
                localized(context.survey(),
                        "Kusura bakmayın, cevabınızı tam anlayamadım.",
                        "Sorry, I did not quite catch your answer.")
        );
        return applyAnswerAndContinue(context, response, cursor, answer, failedAnswer, request.utteranceText());
    }

    private InterviewOrchestrationResponse applyAnswerAndContinue(
            SessionContext context,
            SurveyResponse response,
            QuestionCursor cursor,
            SurveyAnswer answer,
            NormalizedAnswer normalizedAnswer,
            String utteranceText
    ) {
        applyNormalizedAnswer(answer, cursor.question(), normalizedAnswer, cursor.retryCount() + 1);
        recordAnswerSubmission(response, utteranceText);
        SurveyAnswer savedAnswer = surveyAnswerRepository.save(answer);
        context.answersByQuestionId().put(cursor.question().getId(), savedAnswer);
        FlowState postAnswerFlowState = resolveFlowState(context);
        updateCompletionPercent(response, context, postAnswerFlowState.eligibleQuestions());

        if (!savedAnswer.isValid() && savedAnswer.getRetryCount() < context.maxRetryCount()) {
            surveyResponseRepository.save(response);
            return buildProgressResponse(context, response, buildRetryLeadIn(cursor.question(), savedAnswer), postAnswerFlowState);
        }

        QuestionCursor nextCursor = postAnswerFlowState.cursor();
        if (nextCursor.question() == null) {
            finalizeResponse(response, context, null, postAnswerFlowState);
            surveyResponseRepository.save(response);
            return buildTerminalResponse(context, response, buildClosingPrompt(context.survey()), postAnswerFlowState);
        }

        surveyResponseRepository.save(response);
        String transitionPrompt = savedAnswer.isValid()
                ? null
                : localized(context.survey(),
                        "Yanıtın net anlaşılmadığını kısaca belirt, nazikçe devam et ve sonraki soruyu sor.",
                        "Acknowledge that the answer was unclear, move on politely, and ask the next question.");
        return buildProgressResponse(context, response, transitionPrompt, postAnswerFlowState);
    }

    @Override
    public InterviewOrchestrationResponse finishInterview(InterviewFinishRequest request) {
        SessionContext context = loadSessionContext(request.callAttemptId(), request.providerCallId());
        SurveyResponse response = ensureSurveyResponse(context.callAttempt());
        boolean suppressClosingMessage = Boolean.TRUE.equals(request.suppressClosingMessage());
        // An interview that already ended keeps its outcome: finishing a declined or stopped interview
        // must not turn it into PARTIAL or COMPLETED, nor a completed one into ABANDONED.
        if (isTerminalResponse(response)) {
            return buildTerminalResponse(
                    context,
                    response,
                    suppressClosingMessage ? null : resolveTerminalClosingPrompt(context, response)
            );
        }
        FlowState flowState = resolveFlowState(context);
        finalizeResponse(response, context, request.requestedStatus(), flowState);
        surveyResponseRepository.save(response);
        String closingMessage = suppressClosingMessage ? null : buildClosingPrompt(context.survey());
        return buildTerminalResponse(context, response, closingMessage, flowState);
    }

    private SessionContext loadSessionContext(UUID callAttemptId, String providerCallId) {
        CallAttempt callAttempt = resolveCallAttempt(callAttemptId, providerCallId);
        validateProvider(callAttempt);
        Survey survey = callAttempt.getOperation().getSurvey();
        List<SurveyQuestion> questions = surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(survey.getId());
        Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId = new LinkedHashMap<>();
        Map<UUID, QuestionMetadata> metadataByQuestionId = new LinkedHashMap<>();
        Map<String, SurveyQuestion> questionsByCode = new LinkedHashMap<>();
        List<UUID> questionIds = questions.stream().map(SurveyQuestion::getId).toList();
        Map<UUID, List<SurveyQuestionOption>> loadedOptionsByQuestionId = new LinkedHashMap<>();
        for (UUID questionId : questionIds) {
            loadedOptionsByQuestionId.put(questionId, new ArrayList<>());
        }
        if (!questionIds.isEmpty()) {
            surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(questionIds)
                    .stream()
                    .filter(SurveyQuestionOption::isActive)
                    .forEach(option -> loadedOptionsByQuestionId
                            .computeIfAbsent(option.getSurveyQuestion().getId(), ignored -> new ArrayList<>())
                            .add(option));
        }
        for (SurveyQuestion question : questions) {
            optionsByQuestionId.put(question.getId(), List.copyOf(loadedOptionsByQuestionId.getOrDefault(question.getId(), List.of())));
            metadataByQuestionId.put(question.getId(), extractQuestionMetadata(question));
            questionsByCode.put(normalize(question.getCode()), question);
        }

        SurveyResponse response = surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(callAttempt.getId()).orElse(null);
        Map<UUID, SurveyAnswer> answersByQuestionId = new LinkedHashMap<>();
        if (response != null) {
            surveyAnswerRepository.findAllBySurveyResponse_IdAndDeletedAtIsNull(response.getId())
                    .forEach(answer -> answersByQuestionId.put(answer.getSurveyQuestion().getId(), answer));
        }

        int maxRetryCount = survey.getMaxRetryPerQuestion() == null || survey.getMaxRetryPerQuestion() < 1
                ? 2
                : survey.getMaxRetryPerQuestion();
        return new SessionContext(callAttempt, survey, questions, optionsByQuestionId, answersByQuestionId, maxRetryCount, metadataByQuestionId, questionsByCode);
    }

    private CallAttempt resolveCallAttempt(UUID callAttemptId, String providerCallId) {
        if (callAttemptId != null) {
            return callAttemptRepository.findAndLockById(callAttemptId)
                    .orElseThrow(() -> new NotFoundException("Call attempt not found: " + callAttemptId));
        }
        if (providerCallId != null && !providerCallId.isBlank()) {
            return callAttemptRepository.findAndLockByProviderCallId(CallProvider.ELEVENLABS, providerCallId.trim())
                    .orElseThrow(() -> new NotFoundException("Call attempt not found for provider call: " + providerCallId));
        }
        throw new ValidationException("callAttemptId or providerCallId is required");
    }

    private void validateProvider(CallAttempt callAttempt) {
        if (callAttempt.getProvider() != CallProvider.ELEVENLABS) {
            throw new ValidationException("Interview tools are only enabled for ElevenLabs call attempts");
        }
    }

    private SurveyResponse ensureSurveyResponse(CallAttempt callAttempt) {
        return surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(callAttempt.getId())
                .orElseGet(() -> surveyResponseRepository.save(createSurveyResponse(callAttempt)));
    }

    private SurveyResponse createSurveyResponse(CallAttempt callAttempt) {
        SurveyResponse response = new SurveyResponse();
        response.setCompany(callAttempt.getCompany());
        response.setSurvey(callAttempt.getOperation().getSurvey());
        response.setOperation(callAttempt.getOperation());
        response.setOperationContact(callAttempt.getOperationContact());
        response.setCallAttempt(callAttempt);
        response.setRespondentPhone(OperationContactPhoneResolver.resolveDisplayPhoneNumber(callAttempt.getOperationContact()));
        response.setStartedAt(callAttempt.getConnectedAt() != null ? callAttempt.getConnectedAt() : OffsetDateTime.now());
        response.setCompletionPercent(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        response.setStatus(SurveyResponseStatus.PARTIAL);
        response.setTranscriptJson("{}");
        return response;
    }

    private InterviewOrchestrationResponse handleOpeningConsent(
            SessionContext context,
            SurveyResponse response,
            InterviewAnswerRequest request
    ) {
        if (!isOpeningConsentPromptDelivered(response)) {
            updateOpeningConsentPromptDelivered(response, true);
            return buildConsentPhaseResponse(context, response, buildOpeningConsentRepeatPrompt(context.survey()));
        }

        if (request.signal() == InterviewConversationSignal.REPEAT_REQUEST) {
            return buildConsentPhaseResponse(context, response, buildOpeningConsentRepeatPrompt(context.survey()));
        }

        if (request.signal() == InterviewConversationSignal.IDENTITY_REQUEST) {
            return buildConsentPhaseResponse(context, response, buildOpeningConsentIdentityPrompt(context));
        }

        String rawInput = trimToNull(request.utteranceText());
        String sanitizedInput = sanitizeUtterance(rawInput);
        if (request.signal() == InterviewConversationSignal.NO_INPUT || sanitizedInput == null || isLikelyAsrArtifact(rawInput, sanitizedInput)) {
            return askForConsentAgainOrClose(context, response);
        }

        ConsentDecision decision = classifyOpeningConsentDecision(sanitizedInput);
        if (decision == ConsentDecision.ACCEPTED) {
            updateOpeningConsentState(response, CONSENT_GRANTED);
            return buildProgressResponse(context, response, buildConsentAcceptedLeadIn(context.survey()), resolveFlowState(context));
        }
        if (decision == ConsentDecision.DECLINED) {
            FlowState flowState = resolveFlowState(context);
            updateOpeningConsentState(response, CONSENT_DECLINED);
            response.setStatus(SurveyResponseStatus.ABANDONED);
            response.setCompletedAt(OffsetDateTime.now());
            updateCompletionPercent(response, context, flowState.eligibleQuestions());
            return buildTerminalResponse(context, response, buildConsentDeclinedClosingPrompt(context.survey()), flowState);
        }
        return askForConsentAgainOrClose(context, response);
    }

    // After an unclear reply the consent question is asked again, but only as often as a survey question
    // is retried. Then the call ends politely instead of repeating it until the callee hangs up.
    private InterviewOrchestrationResponse askForConsentAgainOrClose(SessionContext context, SurveyResponse response) {
        int clarificationCount = readConsentClarificationCount(response);
        if (clarificationCount >= context.maxRetryCount()) {
            FlowState flowState = resolveFlowState(context);
            updateOpeningConsentState(response, CONSENT_UNCLEAR);
            response.setStatus(SurveyResponseStatus.ABANDONED);
            response.setCompletedAt(OffsetDateTime.now());
            updateCompletionPercent(response, context, flowState.eligibleQuestions());
            return buildTerminalResponse(context, response, buildConsentUnclearClosingPrompt(context.survey()), flowState);
        }
        updateConsentClarificationCount(response, clarificationCount + 1);
        return buildConsentPhaseResponse(context, response, buildOpeningConsentClarificationPrompt(context.survey()));
    }

    private InterviewOrchestrationResponse handleOpeningGreeting(
            SessionContext context,
            SurveyResponse response,
            InterviewAnswerRequest request
    ) {
        if (!isOpeningGreetingLike(request)) {
            return buildConsentPhaseResponse(context, response, null);
        }
        return deliverOpening(context, response);
    }

    // Returns the opening (the consent question, or the intro and the first question) and records
    // that the greeting phase is over.
    private InterviewOrchestrationResponse deliverOpening(SessionContext context, SurveyResponse response) {
        updateOpeningGreetingReceived(response, true);
        if (isOpeningConsentPending(context.survey(), response)) {
            updateOpeningConsentPromptDelivered(response, true);
            return buildConsentPhaseResponse(context, response, buildOpeningConsentRepeatPrompt(context.survey()));
        }
        return buildProgressResponse(context, response, buildOpeningLeadIn(context), resolveFlowState(context));
    }

    private SurveyAnswer createSurveyAnswer(SurveyResponse response, SurveyQuestion question) {
        SurveyAnswer answer = new SurveyAnswer();
        answer.setCompany(response.getCompany());
        answer.setSurveyResponse(response);
        answer.setSurveyQuestion(question);
        answer.setAnswerType(question.getQuestionType());
        answer.setRetryCount(0);
        answer.setValid(false);
        answer.setAnswerJson("{}");
        return answer;
    }

    private QuestionCursor determineCurrentQuestion(
            List<SurveyQuestion> questions,
            Map<UUID, SurveyAnswer> answersByQuestionId,
            int maxRetryCount
    ) {
        return determineCurrentQuestion(questions, answersByQuestionId, maxRetryCount, null);
    }

    private QuestionCursor determineCurrentQuestion(
            List<SurveyQuestion> questions,
            Map<UUID, SurveyAnswer> answersByQuestionId,
            int maxRetryCount,
            SessionContext context
    ) {
        for (SurveyQuestion question : questions) {
            if (context != null && !isQuestionEligible(question, context)) {
                continue;
            }
            SurveyAnswer answer = answersByQuestionId.get(question.getId());
            if (answer == null) {
                return new QuestionCursor(question, 0);
            }
            if (!isQuestionSatisfied(question, answer) && answer.getRetryCount() < maxRetryCount) {
                return new QuestionCursor(question, answer.getRetryCount());
            }
        }
        return new QuestionCursor(null, 0);
    }

    private boolean isQuestionSatisfied(SurveyQuestion question, SurveyAnswer answer) {
        if (answer == null) {
            return false;
        }
        if (answer.isValid()) {
            return true;
        }
        return !question.isRequired()
                && answer.getRetryCount() != null
                && answer.getRetryCount() > 0
                && canTreatInvalidOptionalAnswerAsSatisfied(question);
    }

    private boolean canTreatInvalidOptionalAnswerAsSatisfied(SurveyQuestion question) {
        return question.getQuestionType() == QuestionType.OPEN_ENDED;
    }

    private NormalizedAnswer normalizeAnswer(
            SessionContext context,
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            InterviewAnswerRequest request,
            SurveyAnswer existingAnswer
    ) {
        InterviewConversationSignal signal = request.signal() == null ? InterviewConversationSignal.ANSWER : request.signal();
        String rawInput = trimToNull(request.utteranceText());
        String raw = sanitizeUtterance(rawInput);
        if (signal == InterviewConversationSignal.NO_INPUT || raw == null || isLikelyAsrArtifact(rawInput, raw)) {
            return NormalizedAnswer.invalid("No clear answer captured from the caller", rawInput);
        }

        // On a yes/no question answered with an explicit yes or no, a special-answer alias that is itself a
        // yes/no word does not turn it into a refusal: "hayir istemiyorum" means no. A bare "istemiyorum" stays a refusal.
        boolean answeredYesOrNo = question.getQuestionType() == QuestionType.SINGLE_CHOICE
                && looksLikeYesNo(options)
                && containsAnyWords(normalize(raw), "evet", "hayir", "yok", "yes", "no");
        SpecialAnswerMatch specialAnswer = matchSpecialAnswer(question, raw, answeredYesOrNo);
        if (specialAnswer != null) {
            return NormalizedAnswer.validSpecial(raw, specialAnswer.code(), specialAnswer.label());
        }

        if (question.getQuestionType() == QuestionType.OPEN_ENDED) {
            String clarificationLabel = extractSingleClarificationLabel(existingAnswer);
            if (clarificationLabel != null) {
                // Rejection is checked first: "Hayir, o degil" also contains the affirmation word "o".
                if (looksLikeClarificationRejection(raw)) {
                    return NormalizedAnswer.invalidWithClarification(
                            "Clarified answer was rejected",
                            raw,
                            isTurkish(question.getSurvey())
                                    ? "Değilse lütfen cevabınızı tekrar söyleyin."
                                    : "If not, please say your answer again."
                    );
                }
                if (looksLikeClarificationAffirmation(raw)) {
                    return NormalizedAnswer.validText(raw, clarificationLabel, List.of(clarificationLabel));
                }
            }
        }

        return switch (question.getQuestionType()) {
            case OPEN_ENDED -> normalizeOpenEnded(context, question, raw);
            case NUMBER -> normalizeNumber(raw);
            case RATING -> normalizeRating(question, raw);
            case SINGLE_CHOICE -> normalizeSingleChoice(question, options, raw);
            case MULTI_CHOICE -> normalizeMultiChoice(question, options, raw);
        };
    }

    private boolean looksLikeClarificationAffirmation(String raw) {
        return containsAnyWords(normalize(raw),
                "evet",
                "aynen",
                "dogru",
                "o",
                "begeniyorum",
                "beniyorum",
                "seviyorum");
    }

    private boolean looksLikeClarificationRejection(String raw) {
        return containsAnyWords(normalize(raw),
                "hayir",
                "yok",
                "degil",
                "begenmiyorum",
                "benmiyorum",
                "sevmiyorum");
    }

    private boolean containsAnyWords(String normalizedText, String... phrases) {
        for (String phrase : phrases) {
            if (containsWords(normalizedText, phrase)) {
                return true;
            }
        }
        return false;
    }

    private String extractSingleClarificationLabel(SurveyAnswer answer) {
        String clarificationPrompt = answer == null ? null : extractClarificationPrompt(answer);
        if (clarificationPrompt == null) {
            return null;
        }

        String trimmed = clarificationPrompt.trim();
        String suffix = " mi demek istediniz?";
        if (trimmed.endsWith(suffix)) {
            return trimToNull(trimmed.substring(0, trimmed.length() - suffix.length()));
        }

        String englishPrefix = "Did you mean ";
        if (trimmed.startsWith(englishPrefix) && trimmed.endsWith("?")) {
            return trimToNull(trimmed.substring(englishPrefix.length(), trimmed.length() - 1));
        }

        return null;
    }

    private NormalizedAnswer normalizeSingleChoice(SurveyQuestion question, List<SurveyQuestionOption> options, String raw) {
        // An answer that is exactly one option's label wins before any contextual heuristic.
        SurveyQuestionOption exactLabelMatch = matchExactOptionLabel(options, raw);
        if (exactLabelMatch != null) {
            return NormalizedAnswer.validOption(raw, exactLabelMatch, List.of(exactLabelMatch.getLabel()));
        }

        if (looksLikeYesNo(options)) {
            SurveyQuestionOption matchedYesNo = matchYesNoOption(options, raw);
            if (matchedYesNo != null) {
                return NormalizedAnswer.validOption(raw, matchedYesNo, List.of(matchedYesNo.getLabel()));
            }
        }

        SurveyQuestionOption implicitBinaryMatch = matchImplicitBinaryChoice(options, raw);
        if (implicitBinaryMatch != null) {
            return NormalizedAnswer.validOption(raw, implicitBinaryMatch, List.of(implicitBinaryMatch.getLabel()));
        }

        SurveyQuestionOption contextualMatch = matchContextualSingleChoice(question, options, raw);
        if (contextualMatch != null) {
            return NormalizedAnswer.validOption(raw, contextualMatch, List.of(contextualMatch.getLabel()));
        }

        SurveyQuestionOption directMatch = matchOption(question, options, raw);
        if (directMatch != null) {
            return NormalizedAnswer.validOption(raw, directMatch, List.of(directMatch.getLabel()));
        }
        SemanticOptionDecision semanticDecision = classifySingleChoiceSemantically(question, options, raw);
        if (semanticDecision.matchedOption() != null) {
            return NormalizedAnswer.validOption(raw, semanticDecision.matchedOption(), List.of(semanticDecision.matchedOption().getLabel()));
        }
        if (semanticDecision.clarificationPrompt() != null) {
            return NormalizedAnswer.invalidWithClarification("Answer requires clarification", raw, semanticDecision.clarificationPrompt());
        }
        return NormalizedAnswer.invalid("Answer did not match a known option", raw);
    }

    private NormalizedAnswer normalizeMultiChoice(SurveyQuestion question, List<SurveyQuestionOption> options, String raw) {
        Set<SurveyQuestionOption> matches = new LinkedHashSet<>();
        for (String token : splitMultiValue(raw)) {
            SurveyQuestionOption option = matchOption(question, options, token);
            if (option != null) {
                matches.add(option);
            }
        }
        if (matches.isEmpty()) {
            return NormalizedAnswer.invalid("Answer did not match any known options", raw);
        }
        return NormalizedAnswer.validMulti(raw, matches.stream().map(SurveyQuestionOption::getLabel).toList());
    }

    private NormalizedAnswer normalizeRating(SurveyQuestion question, String raw) {
        Integer min = extractRatingMin(question);
        Integer max = extractRatingMax(question);
        Integer parsed = extractNumber(raw);
        if (parsed == null) {
            parsed = extractSemanticRating(question, raw, min, max);
        }
        if (parsed == null || parsed < min || parsed > max) {
            return NormalizedAnswer.invalid("Rating answer must be between " + min + " and " + max, raw);
        }
        return NormalizedAnswer.validNumber(raw, BigDecimal.valueOf(parsed));
    }

    private NormalizedAnswer normalizeNumber(String raw) {
        Integer parsed = extractNumber(raw);
        if (parsed == null) {
            return NormalizedAnswer.invalid("Number answer could not be parsed", raw);
        }
        return NormalizedAnswer.validNumber(raw, BigDecimal.valueOf(parsed));
    }

    private NormalizedAnswer normalizeOpenEnded(SessionContext context, SurveyQuestion question, String raw) {
        String correctedRaw = applyQuestionAwareOpenEndedCorrections(question, raw);
        if (shouldAskToRepeatOpenEnded(question, raw, correctedRaw)) {
            return NormalizedAnswer.invalidWithClarification(
                    "Open-ended answer requires clarification",
                    raw,
                    localized(
                            question.getSurvey(),
                            "Yanitinizi kisa ve net bir sekilde bir kez daha soyler misiniz?",
                            "Could you please repeat your answer briefly and clearly?"
                    )
            );
        }

        if (isOccupationOpenEndedContext(question)) {
            return normalizeOccupationOpenEnded(question, raw, correctedRaw);
        }

        TurkeyGeoDataService.GeoScope geoScope = extractGeoScope(question);
        if (geoScope == null) {
            return normalizeOpenEndedEntity(context, question, raw, correctedRaw);
        }

        TurkeyGeoDataService.GeoMatchResult match = turkeyGeoDataService.match(correctedRaw, geoScope);
        if (match.candidate() != null && match.confident()) {
            List<String> normalizedValues = new ArrayList<>();
            normalizedValues.add(match.candidate().label());
            if (match.candidate().cityCode() != null) {
                normalizedValues.add(match.candidate().cityCode());
            }
            if (match.candidate().districtName() != null) {
                normalizedValues.add(match.candidate().districtName());
            }
            return NormalizedAnswer.validText(raw, match.candidate().label(), normalizedValues.stream().distinct().toList());
        }

        if (match.candidate() != null && !match.clarificationLabels().isEmpty() && match.score() >= 0.58d) {
            return NormalizedAnswer.invalidWithClarification(
                    "Geo answer requires clarification",
                    raw,
                    buildClarificationPrompt(question.getSurvey(), match.clarificationLabels())
            );
        }

        return normalizeOpenEndedEntity(context, question, raw, correctedRaw);
    }

    private NormalizedAnswer normalizeOpenEndedEntity(SessionContext context, SurveyQuestion question, String raw, String candidateText) {
        List<AutoEntityEntry> entries = extractAutoEntityEntries(context, question);
        if (entries.isEmpty()) {
            return buildOpenEndedFreeTextAnswer(raw, candidateText);
        }

        String normalizedCandidate = normalize(candidateText);
        List<AutoEntityScore> scored = new ArrayList<>();
        for (AutoEntityEntry entry : entries) {
            double score = scoreEntityAliases(normalizedCandidate, entry.aliases());
            if (score > 0.30d) {
                scored.add(new AutoEntityScore(entry, score));
            }
        }
        scored.sort((left, right) -> Double.compare(right.score(), left.score()));
        if (scored.isEmpty()) {
            return buildOpenEndedFreeTextAnswer(raw, candidateText);
        }

        AutoEntityScore best = scored.getFirst();
        AutoEntityScore second = scored.size() > 1 ? scored.get(1) : null;
        if (best.score() >= 0.93d || (best.score() >= 0.82d && (second == null || best.score() - second.score() >= 0.12d))) {
            return NormalizedAnswer.validText(raw, best.entry().label(), List.of(best.entry().label()));
        }
        if (best.score() >= 0.58d) {
            List<String> labels = scored.stream().map(item -> item.entry().label()).distinct().limit(3).toList();
            return NormalizedAnswer.invalidWithClarification(
                    "Named entity answer requires clarification",
                    raw,
                    buildClarificationPrompt(question.getSurvey(), labels)
            );
        }
        return buildOpenEndedFreeTextAnswer(raw, candidateText);
    }

    private NormalizedAnswer buildOpenEndedFreeTextAnswer(String raw, String candidateText) {
        String sanitizedCandidate = trimToNull(candidateText);
        if (sanitizedCandidate == null) {
            return NormalizedAnswer.validText(raw);
        }
        String sanitizedRaw = trimToNull(raw);
        if (sanitizedRaw != null && normalize(sanitizedRaw).equals(normalize(sanitizedCandidate))) {
            return NormalizedAnswer.validText(raw);
        }
        List<String> normalizedValues = new ArrayList<>();
        normalizedValues.add(sanitizedCandidate);
        if (sanitizedRaw != null) {
            normalizedValues.add(sanitizedRaw);
        }
        return NormalizedAnswer.validText(raw, sanitizedCandidate, normalizedValues.stream().distinct().toList());
    }

    // Any answer is kept: no fixed list holds every occupation ("veterinerim", "kasiyerim"). The list
    // only turns a recognized occupation into a clean label ("ogretmenim" -> "Ogretmen").
    private NormalizedAnswer normalizeOccupationOpenEnded(SurveyQuestion question, String raw, String correctedRaw) {
        String answerText = trimToNull(correctedRaw);
        if (answerText == null || normalize(answerText).isBlank()) {
            return occupationNotRecognized(question, raw);
        }
        String occupationLabel = recognizeOccupationLabel(answerText);
        if (occupationLabel != null) {
            return NormalizedAnswer.validText(raw, occupationLabel, List.of(occupationLabel));
        }
        return buildOpenEndedFreeTextAnswer(raw, answerText);
    }

    // Returns the occupation in the respondent's own spelling, without the "I am" ending
    // ("ogretmenim", "emekliyim", "ev hanimiyim"), when it is one of the known occupations.
    private String recognizeOccupationLabel(String answerText) {
        String spoken = answerText.toLowerCase(TURKISH)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .replaceAll("^ben\\s+", "")
                .replaceAll("\\s+ben$", "");
        // Without the ending first: the list also holds "emekliyim", but the label is "Emekli".
        for (String candidate : List.of(PERSONAL_ENDING.matcher(spoken).replaceFirst(""), spoken)) {
            if (!candidate.isBlank() && KNOWN_OCCUPATIONS_NORMALIZED.contains(normalize(candidate))) {
                return candidate.substring(0, 1).toUpperCase(TURKISH) + candidate.substring(1);
            }
        }
        return null;
    }

    private NormalizedAnswer occupationNotRecognized(SurveyQuestion question, String raw) {
        return NormalizedAnswer.invalidWithClarification(
                "Occupation answer not recognized",
                raw,
                localized(
                        question.getSurvey(),
                        "Mesleğinizi anlayamadım. Lütfen tekrar edebilir misiniz?",
                        "I couldn't understand your occupation. Could you please repeat it?"
                )
        );
    }

    private String applyQuestionAwareOpenEndedCorrections(SurveyQuestion question, String raw) {
        String corrected = raw;
        if (isCivicOpenEndedContext(question)) {
            corrected = replacePattern(corrected,
                    "(?iu)\\b(?:şehzad[ıi]n|sehzad[ıi]n|sehradin|şehirin|sehirin)\\s+sorunlar[ıi]yla\\b",
                    "şehrin sorunlarıyla");
            corrected = replacePattern(corrected,
                    "(?iu)\\b(?:şehzad[ıi]n|sehzad[ıi]n|sehradin|şehirin|sehirin)\\s+sorunlar[ıi]\\b",
                    "şehrin sorunları");
            corrected = replacePattern(corrected, "(?iu)\\b(?:şehzad[ıi]n|sehzad[ıi]n|sehradin|şehirin|sehirin)\\b", "şehrin");
        }
        if (isTransportOpenEndedContext(question)) {
            corrected = replacePattern(corrected, "(?iu)\\balt\\s+yap[ıi]\\b", "altyapı");
            corrected = replacePattern(corrected, "(?iu)\\b(?:ulaş[ıi]n|ulasin)\\b", "ulaşım");
        }
        if (isOccupationOpenEndedContext(question)) {
            // "ev ekonomisi" → "ev hanımı" (bilinen ASR karışıklığı)
            corrected = replacePattern(corrected, "(?iu)\\bev\\s+ekonomis[iy]\\b", "ev hanımı");
            // "emeklilik" → "emekli" (durum yerine kişi)
            corrected = replacePattern(corrected, "(?iu)\\bemeklili[ğg][iy]\\b", "emekli");
            // "öğrencilik" → "öğrenci"
            corrected = replacePattern(corrected, "(?iu)\\b[öo][ğg]rencili[ğg][iy]\\b", "öğrenci");
        }
        return corrected;
    }

    private boolean shouldAskToRepeatOpenEnded(SurveyQuestion question, String raw, String correctedRaw) {
        if (!isCivicOpenEndedContext(question)) {
            return false;
        }
        if (!normalize(raw).equals(normalize(correctedRaw))) {
            return false;
        }
        String normalized = normalize(raw);
        for (String token : SUSPICIOUS_CIVIC_ASR_TOKENS) {
            if (normalized.contains(normalize(token))) {
                return true;
            }
        }
        return false;
    }

    private boolean isCivicOpenEndedContext(SurveyQuestion question) {
        return containsAnyPhrase(normalize(buildOpenEndedContext(question)), CIVIC_OPEN_ENDED_HINTS.toArray(String[]::new));
    }

    private boolean isTransportOpenEndedContext(SurveyQuestion question) {
        return containsAnyPhrase(normalize(buildOpenEndedContext(question)), TRANSPORT_OPEN_ENDED_HINTS.toArray(String[]::new));
    }

    // Only the question itself decides. The survey name used to count too, so in a survey named
    // "Meslek ve Yasam Memnuniyeti" every open-ended question was treated as an occupation question.
    private boolean isOccupationOpenEndedContext(SurveyQuestion question) {
        StringBuilder builder = new StringBuilder();
        appendPromptSentence(builder, question.getCode());
        appendPromptSentence(builder, question.getTitle());
        appendPromptSentence(builder, question.getDescription());
        return containsAnyPhrase(normalize(builder.toString()), OCCUPATION_OPEN_ENDED_HINTS.toArray(String[]::new));
    }

    private String buildOpenEndedContext(SurveyQuestion question) {
        StringBuilder builder = new StringBuilder();
        appendPromptSentence(builder, question.getSurvey() == null ? null : question.getSurvey().getName());
        appendPromptSentence(builder, question.getCode());
        appendPromptSentence(builder, question.getTitle());
        appendPromptSentence(builder, question.getDescription());
        return builder.toString();
    }

    private String replacePattern(String value, String regex, String replacement) {
        return Pattern.compile(regex).matcher(value).replaceAll(replacement);
    }

    private SurveyQuestionOption matchYesNoOption(List<SurveyQuestionOption> options, String raw) {
        Boolean polarity = resolveYesNoPolarity(normalize(raw));
        if (polarity == null) {
            return null;
        }
        Predicate<SurveyQuestionOption> matchesPolarity = polarity ? this::isYesOption : this::isNoOption;
        return options.stream().filter(matchesPolarity).findFirst().orElse(null);
    }

    // TRUE for yes, FALSE for no, null when unclear. Explicit words ("evet", "hayir") outweigh softer
    // ones ("tabii", "dogru", "uygun"), and a soft yes followed by "degil" counts as no, so
    // "tabii ki hayir" and "dogru degil" are no answers. Explicit yes and no together is unclear.
    private Boolean resolveYesNoPolarity(String normalized) {
        String[] tokens = normalized.split("\\s+");
        boolean explicitYes = false;
        boolean explicitNo = false;
        boolean softYes = false;
        boolean softNo = false;
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index];
            boolean negatedByNextWord = index + 1 < tokens.length && tokens[index + 1].startsWith("degil");
            if (EXPLICIT_YES_WORDS.contains(token)) {
                explicitYes = true;
            } else if (EXPLICIT_NO_WORDS.contains(token)) {
                explicitNo = true;
            } else if (YES_WORDS.contains(token)) {
                if (negatedByNextWord) {
                    softNo = true;
                } else {
                    softYes = true;
                }
            } else if (NO_WORDS.contains(token) || token.startsWith("degil")) {
                softNo = true;
            }
        }
        if (explicitYes != explicitNo) {
            return explicitYes;
        }
        if (explicitYes) {
            return null;
        }
        if (softYes != softNo) {
            return softYes;
        }
        return null;
    }

    private SurveyQuestionOption matchImplicitBinaryChoice(List<SurveyQuestionOption> options, String raw) {
        String normalized = normalize(raw);
        Boolean polarity = resolveYesNoPolarity(normalized);
        boolean hasPositiveSignal = Boolean.TRUE.equals(polarity);
        boolean hasNegativeSignal = Boolean.FALSE.equals(polarity);
        if (!hasPositiveSignal && !hasNegativeSignal) {
            return null;
        }

        SurveyQuestionOption exactKnowledgePositive = null;
        SurveyQuestionOption softKnowledgeNegative = null;
        SurveyQuestionOption heardButUnknown = null;
        SurveyQuestionOption neverHeard = null;

        for (SurveyQuestionOption option : options) {
            String normalizedOption = normalize(option.getLabel() + " " + option.getOptionCode() + " " + option.getValue());
            if ((normalizedOption.contains("taniyorum") || normalizedOption.contains("biliyorum"))
                    && !normalizedOption.contains("tanimiyorum")) {
                if (normalizedOption.contains("biraz")) {
                    if (exactKnowledgePositive == null) {
                        exactKnowledgePositive = option;
                    }
                } else if (exactKnowledgePositive == null || normalizedOption.equals("taniyorum")) {
                    exactKnowledgePositive = option;
                }
            }
            if (normalizedOption.contains("duydum") && normalizedOption.contains("tanimiyorum")) {
                heardButUnknown = option;
            }
            if (normalizedOption.contains("hic duymadim")) {
                neverHeard = option;
            }
            if (normalizedOption.contains("tanimiyorum")
                    && !normalizedOption.contains("duydum")
                    && softKnowledgeNegative == null) {
                softKnowledgeNegative = option;
            }
        }

        if (hasPositiveSignal) {
            return exactKnowledgePositive;
        }
        if (hasNegativeSignal) {
            if (normalized.contains("hic duymad")) {
                return neverHeard != null ? neverHeard : heardButUnknown;
            }
            if (normalized.contains("duydum")) {
                return heardButUnknown != null ? heardButUnknown : softKnowledgeNegative;
            }
            if (softKnowledgeNegative != null) {
                return softKnowledgeNegative;
            }
            if (heardButUnknown != null) {
                return heardButUnknown;
            }
            return neverHeard;
        }
        return null;
    }

    // Picks the most specific option instead of the first one that overlaps: an exact match wins, then
    // the longest option text said within the answer ("cok memnunum" -> "Cok memnunum", not "Memnunum"),
    // then the option closest in length to an answer that is only part of it ("memnun degilim" ->
    // "Memnun degilim", not "Hic memnun degilim").
    private SurveyQuestionOption matchOption(SurveyQuestion question, List<SurveyQuestionOption> options, String raw) {
        String normalizedCandidate = normalize(raw);
        if (normalizedCandidate.isBlank()) {
            return null;
        }
        Map<String, List<String>> aliasesByOptionKey = extractOptionAliases(question);
        SurveyQuestionOption bestOption = null;
        int bestScore = Integer.MIN_VALUE;
        for (SurveyQuestionOption option : options) {
            List<String> candidates = new ArrayList<>();
            candidates.add(option.getLabel());
            candidates.add(option.getOptionCode());
            candidates.add(option.getValue());
            candidates.addAll(resolveAliasesForOption(option, aliasesByOptionKey));
            for (String candidate : candidates) {
                String normalizedOption = normalize(candidate);
                if (normalizedOption.isBlank()) {
                    continue;
                }
                int score;
                if (normalizedCandidate.equals(normalizedOption)) {
                    score = 3_000_000;
                } else if (normalizedCandidate.contains(normalizedOption)) {
                    score = 2_000_000 + normalizedOption.length();
                } else if (normalizedOption.contains(normalizedCandidate)) {
                    score = 1_000_000 - (normalizedOption.length() - normalizedCandidate.length());
                } else {
                    continue;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestOption = option;
                }
            }
        }
        return bestOption;
    }

    private Map<String, List<String>> extractOptionAliases(SurveyQuestion question) {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        mergeOptionAliases(question.getSettingsJson(), "aliases", aliases);
        mergeOptionAliases(question.getSettingsJson(), "autoAliases", aliases);
        return aliases;
    }

    private void mergeOptionAliases(String settingsJson, String fieldName, Map<String, List<String>> aliases) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(settingsJson);
            JsonNode aliasesNode = root.get(fieldName);
            if (aliasesNode == null || !aliasesNode.isObject()) {
                return;
            }
            aliasesNode.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isArray()) {
                    return;
                }
                List<String> values = new ArrayList<>();
                entry.getValue().forEach(item -> {
                    String value = trimToNull(item.asText(null));
                    if (value != null) {
                        values.add(value);
                    }
                });
                if (!values.isEmpty()) {
                    String key = normalize(entry.getKey());
                    List<String> merged = new ArrayList<>(aliases.getOrDefault(key, List.of()));
                    merged.addAll(values);
                    aliases.put(key, merged.stream().distinct().toList());
                }
            });
        } catch (Exception ignored) {
        }
    }

    private SpecialAnswerMatch matchSpecialAnswer(SurveyQuestion question, String raw, boolean ignoreYesNoWords) {
        String normalizedCandidate = normalize(raw);
        Map<String, List<String>> specialAnswers = extractSpecialAnswers(question);
        for (Map.Entry<String, List<String>> entry : specialAnswers.entrySet()) {
            for (String alias : entry.getValue()) {
                String normalizedAlias = normalize(alias);
                if (normalizedAlias.isBlank()) {
                    continue;
                }
                if (ignoreYesNoWords && (YES_WORDS.contains(normalizedAlias) || NO_WORDS.contains(normalizedAlias))) {
                    continue;
                }
                if (containsWords(normalizedCandidate, normalizedAlias)) {
                    return new SpecialAnswerMatch(entry.getKey(), humanizeCode(entry.getKey()));
                }
            }
        }
        return null;
    }

    @SafeVarargs
    private static Map<String, List<String>> orderedSpecialAnswers(Map.Entry<String, List<String>>... entries) {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : entries) {
            ordered.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(ordered);
    }

    // Whole-word match on normalized text: "pas" must not match inside "bayrampasa", and a short
    // reply such as "istiyorum" must not match a longer alias like "gecmek istiyorum".
    private boolean containsWords(String normalizedText, String normalizedPhrase) {
        if (normalizedText.isBlank() || normalizedPhrase.isBlank()) {
            return false;
        }
        return (" " + normalizedText + " ").contains(" " + normalizedPhrase + " ");
    }

    private Map<String, List<String>> extractSpecialAnswers(SurveyQuestion question) {
        Map<String, List<String>> specialAnswers = new LinkedHashMap<>();
        DEFAULT_SPECIAL_ANSWERS.forEach((code, aliases) -> specialAnswers.put(code, List.copyOf(aliases)));

        if (question.getSettingsJson() == null || question.getSettingsJson().isBlank()) {
            return specialAnswers;
        }
        try {
            JsonNode root = objectMapper.readTree(question.getSettingsJson());
            JsonNode specialAnswersNode = root.get("specialAnswers");
            if (specialAnswersNode == null || !specialAnswersNode.isObject()) {
                return specialAnswers;
            }
            specialAnswersNode.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isArray()) {
                    return;
                }
                List<String> values = new ArrayList<>();
                entry.getValue().forEach(item -> {
                    String value = trimToNull(item.asText(null));
                    if (value != null) {
                        values.add(value);
                    }
                });
                if (!values.isEmpty()) {
                    String key = normalize(entry.getKey());
                    List<String> mergedAliases = new ArrayList<>(specialAnswers.getOrDefault(key, List.of()));
                    mergedAliases.addAll(values);
                    specialAnswers.put(key, mergedAliases.stream().distinct().toList());
                }
            });
            return specialAnswers;
        } catch (Exception ignored) {
            return specialAnswers;
        }
    }

    private SemanticOptionDecision classifySingleChoiceSemantically(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            String raw
    ) {
        String normalizedCandidate = normalize(raw);
        Map<String, List<String>> aliasesByOptionKey = extractOptionAliases(question);
        List<OptionSemanticScore> scoredOptions = new ArrayList<>();

        for (SurveyQuestionOption option : options) {
            double score = scoreOptionSemantics(option, aliasesByOptionKey, normalizedCandidate);
            if (score > 0) {
                scoredOptions.add(new OptionSemanticScore(option, score));
            }
        }

        scoredOptions.sort((left, right) -> Double.compare(right.score(), left.score()));
        if (scoredOptions.isEmpty()) {
            return new SemanticOptionDecision(null, null);
        }

        OptionSemanticScore best = scoredOptions.getFirst();
        OptionSemanticScore second = scoredOptions.size() > 1 ? scoredOptions.get(1) : null;
        if (best.score() >= 0.92d || (best.score() >= 0.74d && (second == null || best.score() - second.score() >= 0.18d))) {
            return new SemanticOptionDecision(best.option(), null);
        }

        if (best.score() >= 0.48d) {
            List<String> labels = new ArrayList<>();
            labels.add(best.option().getLabel());
            if (second != null) {
                labels.add(second.option().getLabel());
            } else if (options.size() > 1) {
                options.stream()
                        .filter(option -> !option.getId().equals(best.option().getId()))
                        .findFirst()
                        .map(SurveyQuestionOption::getLabel)
                        .ifPresent(labels::add);
            }
            return new SemanticOptionDecision(null, buildClarificationPrompt(question.getSurvey(), labels.stream().distinct().toList()));
        }

        return new SemanticOptionDecision(null, null);
    }

    private double scoreOptionSemantics(
            SurveyQuestionOption option,
            Map<String, List<String>> aliasesByOptionKey,
            String normalizedCandidate
    ) {
        List<String> candidates = new ArrayList<>();
        candidates.add(option.getLabel());
        candidates.add(option.getOptionCode());
        candidates.add(option.getValue());
        candidates.addAll(resolveAliasesForOption(option, aliasesByOptionKey));

        double bestScore = 0d;
        Set<String> utteranceTokens = semanticTokens(normalizedCandidate);
        for (String candidate : candidates) {
            String normalizedOption = normalize(candidate);
            if (normalizedOption.isBlank()) {
                continue;
            }
            if (normalizedCandidate.equals(normalizedOption)) {
                return 1d;
            }
            if (normalizedCandidate.contains(normalizedOption)) {
                bestScore = Math.max(bestScore, 0.96d);
                continue;
            }

            bestScore = Math.max(bestScore, scorePhraseSimilarity(normalizedCandidate, normalizedOption));

            Set<String> optionTokens = semanticTokens(normalizedOption);
            if (optionTokens.isEmpty()) {
                continue;
            }

            long overlap = optionTokens.stream().filter(utteranceTokens::contains).count();
            if (overlap == 0) {
                continue;
            }

            double coverage = overlap / (double) optionTokens.size();
            double utteranceShare = overlap / (double) Math.max(utteranceTokens.size(), 1);
            bestScore = Math.max(bestScore, (coverage * 0.72d) + (utteranceShare * 0.28d));
        }
        return bestScore;
    }

    private List<AutoEntityEntry> extractAutoEntityEntries(SessionContext context, SurveyQuestion question) {
        Map<String, AutoEntityEntry> merged = new LinkedHashMap<>();
        mergeAutoEntityEntries(merged, parseQuestionAutoEntityEntries(question));
        mergeAutoEntityEntries(
                merged,
                operationAutoEntityLexiconService.extractEntriesForQuestion(
                        context.callAttempt().getOperation().getSourcePayloadJson(),
                        question.getId(),
                        question.getCode()
                ).stream()
                        .map(entry -> new AutoEntityEntry(entry.label(), entry.aliases()))
                        .toList()
        );
        return List.copyOf(merged.values());
    }

    private void mergeAutoEntityEntries(Map<String, AutoEntityEntry> target, List<AutoEntityEntry> incomingEntries) {
        for (AutoEntityEntry incoming : incomingEntries) {
            String key = normalize(incoming.label());
            if (key.isBlank()) {
                continue;
            }
            LinkedHashSet<String> aliases = new LinkedHashSet<>();
            if (target.containsKey(key)) {
                aliases.addAll(target.get(key).aliases());
            }
            aliases.addAll(incoming.aliases());
            target.put(key, new AutoEntityEntry(incoming.label(), List.copyOf(aliases)));
        }
    }

    private List<AutoEntityEntry> parseQuestionAutoEntityEntries(SurveyQuestion question) {
        if (question.getSettingsJson() == null || question.getSettingsJson().isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(question.getSettingsJson());
            JsonNode entriesNode = root.path("autoEntityLexicon").path("entries");
            if (!entriesNode.isArray()) {
                return List.of();
            }
            List<AutoEntityEntry> entries = new ArrayList<>();
            entriesNode.forEach(item -> {
                String label = trimToNull(item.path("label").asText(null));
                JsonNode aliasesNode = item.get("aliases");
                if (label == null || aliasesNode == null || !aliasesNode.isArray()) {
                    return;
                }
                List<String> aliases = new ArrayList<>();
                aliasesNode.forEach(aliasNode -> {
                    String alias = trimToNull(aliasNode.asText(null));
                    if (alias != null) {
                        aliases.add(alias);
                    }
                });
                if (!aliases.isEmpty()) {
                    entries.add(new AutoEntityEntry(label, List.copyOf(aliases)));
                }
            });
            return List.copyOf(entries);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private double scoreEntityAliases(String normalizedCandidate, List<String> aliases) {
        double best = 0d;
        for (String alias : aliases) {
            String normalizedAlias = normalize(alias);
            if (normalizedAlias.isBlank()) {
                continue;
            }
            if (normalizedCandidate.equals(normalizedAlias)) {
                return 1d;
            }
            if (normalizedCandidate.contains(normalizedAlias) || normalizedAlias.contains(normalizedCandidate)) {
                Set<String> aliasTokenSet = new HashSet<>(Arrays.asList(normalizedAlias.split("\\s+")));
                long unexplainedTokens = Arrays.stream(normalizedCandidate.split("\\s+"))
                        .filter(t -> t.length() >= 3 && !aliasTokenSet.contains(t))
                        .count();
                if (unexplainedTokens == 0) {
                    best = Math.max(best, 0.96d);
                }
            }
            best = Math.max(best, scorePhraseSimilarity(normalizedCandidate, normalizedAlias));
        }
        return best;
    }

    private double scorePhraseSimilarity(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return 0d;
        }
        if (left.equals(right)) {
            return 1d;
        }
        Set<String> leftTokens = semanticTokens(left);
        Set<String> rightTokens = semanticTokens(right);
        Set<String> shared = new LinkedHashSet<>(leftTokens);
        shared.retainAll(rightTokens);
        Set<String> union = new LinkedHashSet<>(leftTokens);
        union.addAll(rightTokens);
        double tokenScore = union.isEmpty() ? 0d : (double) shared.size() / union.size();
        int maxLength = Math.max(left.length(), right.length());
        double editScore = maxLength == 0 ? 1d : 1d - ((double) levenshtein(left, right) / maxLength);
        return (tokenScore * 0.45d) + (editScore * 0.55d);
    }

    private int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index += 1) {
            previous[index] = index;
        }
        for (int i = 1; i <= left.length(); i += 1) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j += 1) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private Set<String> semanticTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = normalize(value);
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank() || SEMANTIC_FILLER_TOKENS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private String buildClarificationPrompt(Survey survey, List<String> labels) {
        if (labels.isEmpty()) {
            return null;
        }
        if (labels.size() == 1) {
            return isTurkish(survey)
                    ? labels.getFirst() + " mi demek istediniz?"
                    : "Did you mean " + labels.getFirst() + "?";
        }
        String joinedLabels = String.join(isTurkish(survey) ? " mı, yoksa " : " or ", labels);
        return isTurkish(survey)
                ? "Hangisi daha yakın: " + joinedLabels + "?"
                : "Which one is closer: " + joinedLabels + "?";
    }

    private Integer extractSemanticRating(SurveyQuestion question, String raw, Integer min, Integer max) {
        String normalized = normalize(raw);
        Integer matchedValue = null;
        String matchedKey = null;
        for (Map<String, Integer> semanticMap : List.of(RATING_SEMANTIC_TR_1_5, RATING_SEMANTIC_EN_1_5)) {
            for (Map.Entry<String, Integer> entry : semanticMap.entrySet()) {
                if (!normalized.contains(entry.getKey())) {
                    continue;
                }
                if (matchedKey == null || entry.getKey().length() > matchedKey.length()) {
                    matchedValue = entry.getValue();
                    matchedKey = entry.getKey();
                }
            }
        }
        if (matchedValue == null) {
            return null;
        }
        // "onemli degil" and "cok onemli degil" negate a positive phrase: treat them as "not important".
        if (matchedValue >= 3 && containsWords(normalized, matchedKey + " degil")) {
            matchedValue = 2;
        }
        if (max != null && max == 10) {
            return switch (matchedValue) {
                case 1 -> 1;
                case 2 -> 3;
                case 3 -> 5;
                case 4 -> 8;
                default -> 10;
            };
        }
        return Math.max(min == null ? 1 : min, Math.min(max == null ? 5 : max, matchedValue));
    }

    private List<String> resolveAliasesForOption(SurveyQuestionOption option, Map<String, List<String>> aliasesByOptionKey) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(normalize(option.getOptionCode()));
        keys.add(normalize(option.getValue()));
        keys.add(normalize(option.getLabel()));

        List<String> aliases = new ArrayList<>();
        for (String key : keys) {
            aliases.addAll(aliasesByOptionKey.getOrDefault(key, List.of()));
        }
        return aliases;
    }

    private String humanizeCode(String code) {
        String normalized = trimToNull(code);
        if (normalized == null) {
            return "";
        }
        String[] parts = normalized.split("_");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            words.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
        }
        return words.isEmpty() ? normalized : String.join(" ", words);
    }

    private List<String> splitMultiValue(String raw) {
        String normalized = normalize(raw)
                .replace(" ve ", ",")
                .replace(" and ", ",");
        List<String> values = new ArrayList<>();
        for (String token : normalized.split(",")) {
            String value = trimToNull(token);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private Integer extractNumber(String raw) {
        return SpokenNumberParser.parse(normalize(raw));
    }

    private SurveyQuestionOption matchContextualSingleChoice(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            String raw
    ) {
        SurveyQuestionOption ageBucketMatch = matchAgeBucketOption(question, options, raw);
        if (ageBucketMatch != null) {
            return ageBucketMatch;
        }

        SurveyQuestionOption educationMatch = matchEducationLevelOption(question, options, raw);
        if (educationMatch != null) {
            return educationMatch;
        }

        return matchElectionPreferenceOption(options, raw);
    }

    private SurveyQuestionOption matchAgeBucketOption(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            String raw
    ) {
        if (!isAgeLikeQuestion(question)) {
            return null;
        }

        Integer age = extractNumber(raw);
        if (age == null) {
            return null;
        }

        for (SurveyQuestionOption option : options) {
            if (matchesAgeBucket(option, age)) {
                return option;
            }
        }
        return null;
    }

    private boolean isAgeLikeQuestion(SurveyQuestion question) {
        String normalizedCode = normalize(question.getCode());
        String normalizedTitle = normalize(question.getTitle());
        return normalizedCode.contains("age")
                || normalizedCode.contains("yas")
                || normalizedTitle.contains("age")
                || normalizedTitle.contains("yas");
    }

    // Reads the bucket from the numbers in the option text: "25-34" is a range, "65+" or "65 ve uzeri"
    // is open-ended upwards, "18 yas alti" downwards. The label is preferred; codes like "option_1"
    // carry unrelated digits.
    private boolean matchesAgeBucket(SurveyQuestionOption option, int age) {
        for (String text : java.util.Arrays.asList(option.getLabel(), option.getValue(), option.getOptionCode())) {
            String normalized = normalize(text);
            List<Integer> bounds = java.util.Arrays.stream(normalized.split("\\s+"))
                    .filter(token -> !token.isEmpty() && token.length() <= 3 && token.chars().allMatch(Character::isDigit))
                    .map(Integer::parseInt)
                    .toList();
            if (bounds.size() >= 2) {
                return age >= bounds.get(0) && age <= bounds.get(1);
            }
            if (bounds.size() == 1) {
                int bound = bounds.getFirst();
                if (text.contains("+") || containsAnyWords(normalized, "uzeri", "ustu", "yukari", "over", "plus", "above")) {
                    return age >= bound;
                }
                if (containsAnyWords(normalized, "alti", "altinda", "under", "below")) {
                    return age < bound;
                }
                return age == bound;
            }
        }
        return false;
    }

    private SurveyQuestionOption matchEducationLevelOption(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            String raw
    ) {
        if (!isEducationLikeQuestion(question, options)) {
            return null;
        }

        String normalized = normalize(raw);
        if (normalized.isBlank()) {
            return null;
        }

        if (containsAnyPhrase(normalized, "okur yazar degil", "okuma yazma bilmiyorum", "okuma yazmam yok", "oyd")) {
            return findEducationOption(options, "okur yazar degil", "yazar degil", "oyd");
        }
        if (containsAnyPhrase(normalized, "yuksek lisans", "master", "mastir", "lisansustu", "postgraduate")) {
            return findEducationOption(options, "yuksek lisans", "lisansustu", "master");
        }
        if (containsAnyPhrase(normalized, "universite", "lisans", "fakulte", "university", "bachelor")) {
            return findEducationOption(options, "universite", "lisans", "university", "bachelor");
        }
        if (containsAnyPhrase(normalized, "lise", "high school")) {
            return findEducationOption(options, "lise", "high school");
        }
        if (containsAnyPhrase(normalized, "ortaokul", "orta okul", "middle school")) {
            return findEducationOption(options, "ortaokul", "orta okul", "middle school");
        }
        if (containsAnyPhrase(normalized, "ilkokul", "ilk okul", "primary school", "elementary")) {
            return findEducationOption(options, "ilkokul", "ilk okul", "primary", "elementary");
        }
        if (containsAnyPhrase(normalized, "okur yazar", "okuma yazma")) {
            return findEducationOption(options, "okur yazar");
        }
        return null;
    }

    private boolean isEducationLikeQuestion(SurveyQuestion question, List<SurveyQuestionOption> options) {
        String normalizedCode = normalize(question.getCode());
        String normalizedTitle = normalize(question.getTitle());
        if (normalizedCode.contains("education")
                || normalizedCode.contains("egitim")
                || normalizedCode.contains("ogrenim")
                || normalizedTitle.contains("education")
                || normalizedTitle.contains("egitim")
                || normalizedTitle.contains("ogrenim")) {
            return true;
        }
        long educationOptionCount = options.stream()
                .map(option -> normalize(option.getLabel()))
                .filter(label -> containsAnyPhrase(label, "ilkokul", "ortaokul", "lise", "universite", "lisans", "okur yazar"))
                .count();
        return educationOptionCount >= 3;
    }

    private SurveyQuestionOption findEducationOption(List<SurveyQuestionOption> options, String... tokens) {
        for (String token : tokens) {
            String normalizedToken = normalize(token);
            SurveyQuestionOption matched = options.stream()
                    .filter(option -> normalize(option.getLabel() + " " + option.getValue()).contains(normalizedToken))
                    .findFirst()
                    .orElse(null);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    private SurveyQuestionOption matchElectionPreferenceOption(List<SurveyQuestionOption> options, String raw) {
        String normalized = normalize(raw);
        if (normalized.isBlank()) {
            return null;
        }

        if (containsAnyPhrase(normalized,
                "her ikisi",
                "her ikisi de",
                "ikisine gore",
                "ikisi de",
                "ikiside")) {
            return findSingleOptionContainingAny(options, "her ikisi", "ikisi", "both");
        }

        // The party/candidate shortcuts are only for "do you vote by party or by candidate?" questions.
        // On a question that lists parties, "parti" appears in several options and in answers such as
        // "IYI Parti", so matching on it would pick whichever party is listed first.
        SurveyQuestionOption partyPreferenceOption = findSingleOptionContainingAny(options, "partiye", "parti bazli", "party");
        SurveyQuestionOption candidatePreferenceOption = findSingleOptionContainingAny(options, "adaya", "adaylara", "milletvekili", "candidate");
        if (partyPreferenceOption == null || candidatePreferenceOption == null) {
            return null;
        }
        if (containsAnyPhrase(normalized,
                "partiye gore",
                "partiye gore oy",
                "parti bazli",
                "parti tercihi",
                "parti")) {
            return partyPreferenceOption;
        }
        if (containsAnyPhrase(normalized,
                "adaylara gore",
                "adaya gore",
                "milletvekili adaylarina gore",
                "milletvekili adayina gore",
                "aday",
                "milletvekili")) {
            return candidatePreferenceOption;
        }
        return null;
    }

    private SurveyQuestionOption findSingleOptionContainingAny(List<SurveyQuestionOption> options, String... tokens) {
        for (String token : tokens) {
            List<SurveyQuestionOption> matches = options.stream()
                    .filter(option -> normalize(option.getLabel() + " " + option.getOptionCode() + " " + option.getValue()).contains(token))
                    .toList();
            if (matches.size() == 1) {
                return matches.getFirst();
            }
            if (matches.size() > 1) {
                return null;
            }
        }
        return null;
    }

    private SurveyQuestionOption matchExactOptionLabel(List<SurveyQuestionOption> options, String raw) {
        String normalizedCandidate = normalize(raw);
        if (normalizedCandidate.isBlank()) {
            return null;
        }
        List<SurveyQuestionOption> matches = options.stream()
                .filter(option -> normalizedCandidate.equals(normalize(option.getLabel())))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private boolean looksLikeYesNo(List<SurveyQuestionOption> options) {
        if (options.size() != 2) {
            return false;
        }
        return options.stream().anyMatch(this::isYesOption) && options.stream().anyMatch(this::isNoOption);
    }

    private boolean isYesOption(SurveyQuestionOption option) {
        String normalized = normalize(option.getLabel() + " " + option.getOptionCode() + " " + option.getValue());
        return YES_WORDS.stream().anyMatch(normalized::contains);
    }

    private boolean isNoOption(SurveyQuestionOption option) {
        String normalized = normalize(option.getLabel() + " " + option.getOptionCode() + " " + option.getValue());
        return NO_WORDS.stream().anyMatch(normalized::contains);
    }

    private void applyNormalizedAnswer(
            SurveyAnswer answer,
            SurveyQuestion question,
            NormalizedAnswer normalizedAnswer,
            int retryCount
    ) {
        answer.setAnswerType(question.getQuestionType());
        answer.setRetryCount(retryCount);
        answer.setRawInputText(normalizedAnswer.rawText());
        answer.setValid(normalizedAnswer.valid());
        answer.setInvalidReason(normalizedAnswer.invalidReason());
        answer.setSelectedOption(normalizedAnswer.selectedOption());
        answer.setAnswerNumber(normalizedAnswer.numberValue());
        answer.setAnswerText(normalizedAnswer.answerText());
        answer.setAnswerJson(toAnswerJson(question, normalizedAnswer, retryCount));
    }

    private String toAnswerJson(SurveyQuestion question, NormalizedAnswer normalizedAnswer, int retryCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("questionType", question.getQuestionType());
        payload.put("rawText", normalizedAnswer.rawText());
        payload.put("normalizedText", normalizedAnswer.answerText());
        payload.put("normalizedValues", normalizedAnswer.normalizedValues());
        payload.put("answerTags", extractAnswerTags(normalizedAnswer));
        payload.put("specialAnswerCode", normalizedAnswer.specialAnswerCode());
        payload.put("codedThemes", question.getQuestionType() == QuestionType.OPEN_ENDED && normalizedAnswer.specialAnswerCode() == null
                ? extractOpenEndedCodes(question, normalizedAnswer.rawText())
                : List.of());
        payload.put("selectedOptionId", normalizedAnswer.selectedOption() == null ? null : normalizedAnswer.selectedOption().getId());
        payload.put("clarificationPrompt", normalizedAnswer.clarificationPrompt());
        payload.put("retryCount", retryCount);
        payload.put("valid", normalizedAnswer.valid());
        payload.put("invalidReason", normalizedAnswer.invalidReason());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private void finalizeResponse(
            SurveyResponse response,
            SessionContext context,
            SurveyResponseStatus requestedStatus
    ) {
        finalizeResponse(response, context, requestedStatus, resolveFlowState(context));
    }

    private void finalizeResponse(
            SurveyResponse response,
            SessionContext context,
            SurveyResponseStatus requestedStatus,
            FlowState flowState
    ) {
        SurveyResponseStatus status;
        if (requestedStatus == SurveyResponseStatus.ABANDONED) {
            status = SurveyResponseStatus.ABANDONED;
        } else if (flowState.completedRequiredQuestionCount() >= flowState.totalRequiredQuestionCount()) {
            status = SurveyResponseStatus.COMPLETED;
        } else {
            status = SurveyResponseStatus.PARTIAL;
        }
        response.setStatus(status);
        response.setCompletedAt(OffsetDateTime.now());
        updateCompletionPercent(response, context, flowState.eligibleQuestions());
    }

    private void updateCompletionPercent(SurveyResponse response, SessionContext context) {
        updateCompletionPercent(response, context, resolveEligibleQuestions(context));
    }

    private void updateCompletionPercent(
            SurveyResponse response,
            SessionContext context,
            List<SurveyQuestion> questions
    ) {
        if (questions.isEmpty()) {
            response.setCompletionPercent(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            return;
        }
        long addressedCount = questions.stream()
                .filter(question -> context.answersByQuestionId().containsKey(question.getId()))
                .count();
        response.setCompletionPercent(
                BigDecimal.valueOf((addressedCount * 100.0d) / questions.size()).setScale(2, RoundingMode.HALF_UP)
        );
    }

    private boolean areRequiredQuestionsCompleted(SessionContext context) {
        return resolveFlowState(context).eligibleQuestions().stream()
                .filter(SurveyQuestion::isRequired)
                .allMatch(question -> isQuestionSatisfied(question, context.answersByQuestionId().get(question.getId())));
    }

    private InterviewOrchestrationResponse buildProgressResponse(
            SessionContext context,
            SurveyResponse response,
            String leadIn
    ) {
        return buildProgressResponse(context, response, leadIn, resolveFlowState(context));
    }

    private InterviewOrchestrationResponse buildProgressResponse(
            SessionContext context,
            SurveyResponse response,
            String leadIn,
            FlowState flowState
    ) {
        List<SurveyQuestion> eligibleQuestions = flowState.eligibleQuestions();
        QuestionCursor cursor = flowState.cursor();
        if (cursor.question() == null) {
            finalizeResponse(response, context, null, flowState);
            surveyResponseRepository.save(response);
            return buildTerminalResponse(context, response, buildClosingPrompt(context.survey()), flowState);
        }

        SurveyAnswer answer = context.answersByQuestionId().get(cursor.question().getId());
        QuestionMetadata currentMetadata = context.metadataByQuestionId().get(cursor.question().getId());
        QuestionMetadata previousMetadata = findPreviousQuestionMetadata(eligibleQuestions, cursor.question(), context);
        InterviewQuestionPayload payload = toQuestionPayload(
                cursor.question(),
                context.optionsByQuestionId().getOrDefault(cursor.question().getId(), List.of()),
                answer,
                context.maxRetryCount(),
                currentMetadata,
                previousMetadata
        );
        String prompt = leadIn == null ? payload.spokenPrompt() : leadIn + " " + payload.spokenPrompt();
        return buildResponse(context, response, payload, prompt, false, flowState);
    }

    private InterviewOrchestrationResponse buildConsentPhaseResponse(
            SessionContext context,
            SurveyResponse response,
            String prompt
    ) {
        return buildResponse(context, response, null, prompt, false);
    }

    private InterviewOrchestrationResponse buildTerminalResponse(
            SessionContext context,
            SurveyResponse response,
            String closingMessage
    ) {
        return buildTerminalResponse(context, response, closingMessage, resolveFlowState(context));
    }

    private InterviewOrchestrationResponse buildTerminalResponse(
            SessionContext context,
            SurveyResponse response,
            String closingMessage,
            FlowState flowState
    ) {
        return buildResponse(context, response, null, closingMessage, true, flowState);
    }

    private InterviewOrchestrationResponse buildResponse(
            SessionContext context,
            SurveyResponse response,
            InterviewQuestionPayload question,
            String prompt,
            boolean endCall
    ) {
        return buildResponse(context, response, question, prompt, endCall, resolveFlowState(context));
    }

    private InterviewOrchestrationResponse buildResponse(
            SessionContext context,
            SurveyResponse response,
            InterviewQuestionPayload question,
            String prompt,
            boolean endCall,
            FlowState flowState
    ) {
        String cleanedPrompt = sanitizePromptForSpeech(prompt);
        return new InterviewOrchestrationResponse(
                context.callAttempt().getId(),
                response.getId(),
                context.callAttempt().getOperation().getId(),
                context.survey().getId(),
                context.callAttempt().getOperation().getName(),
                context.survey().getName(),
                buildContactName(context.callAttempt()),
                response.getStatus(),
                response.getStatus() == SurveyResponseStatus.COMPLETED || response.getStatus() == SurveyResponseStatus.ABANDONED,
                endCall,
                cleanedPrompt,
                endCall ? cleanedPrompt : null,
                question,
                context.answersByQuestionId().size(),
                flowState.eligibleQuestions().size(),
                flowState.completedRequiredQuestionCount(),
                flowState.totalRequiredQuestionCount()
        );
    }

    private FlowState resolveFlowState(SessionContext context) {
        List<SurveyQuestion> eligibleQuestions = resolveEligibleQuestions(context);
        QuestionCursor cursor = determineCurrentQuestion(eligibleQuestions, context.answersByQuestionId(), context.maxRetryCount());
        int completedRequiredQuestionCount = (int) eligibleQuestions.stream()
                .filter(SurveyQuestion::isRequired)
                .filter(current -> isQuestionSatisfied(current, context.answersByQuestionId().get(current.getId())))
                .count();
        int totalRequiredQuestionCount = (int) eligibleQuestions.stream()
                .filter(SurveyQuestion::isRequired)
                .count();
        return new FlowState(eligibleQuestions, cursor, completedRequiredQuestionCount, totalRequiredQuestionCount);
    }

    private List<SurveyQuestion> resolveEligibleQuestions(SessionContext context) {
        List<SurveyQuestion> eligibleQuestions = new ArrayList<>();
        for (SurveyQuestion question : context.questions()) {
            if (isQuestionEligible(question, context)) {
                eligibleQuestions.add(question);
            }
        }
        return eligibleQuestions;
    }

    private boolean isQuestionEligible(SurveyQuestion question, SessionContext context) {
        BranchConditionSet branchConditionSet = parseBranchConditionSet(question.getBranchConditionJson());
        if (branchConditionSet == null || branchConditionSet.rules().isEmpty()) {
            return true;
        }

        boolean ruleMatch = evaluateBranchRules(branchConditionSet, question, context);
        return switch (branchConditionSet.mode()) {
            case ASK_IF -> ruleMatch;
            case SKIP_IF -> !ruleMatch;
        };
    }

    private boolean evaluateBranchRules(BranchConditionSet branchConditionSet, SurveyQuestion currentQuestion, SessionContext context) {
        List<Boolean> matches = new ArrayList<>();
        for (BranchRule rule : branchConditionSet.rules()) {
            matches.add(evaluateBranchRule(rule, currentQuestion, context));
        }
        if (matches.isEmpty()) {
            return true;
        }
        return switch (branchConditionSet.operator()) {
            case ANY -> matches.stream().anyMatch(Boolean::booleanValue);
            case ALL -> matches.stream().allMatch(Boolean::booleanValue);
        };
    }

    private boolean evaluateBranchRule(BranchRule rule, SurveyQuestion currentQuestion, SessionContext context) {
        SurveyQuestion referencedQuestion = resolveReferencedQuestion(rule, currentQuestion, context);
        if (referencedQuestion == null) {
            return false;
        }

        SurveyAnswer referencedAnswer = context.answersByQuestionId().get(referencedQuestion.getId());
        if (referencedAnswer == null) {
            return false;
        }
        if (rule.validAnswerRequired() && !referencedAnswer.isValid()) {
            return false;
        }

        if (rule.selectedOptionCodes().isEmpty()) {
            return rule.answerTagsAnyOf().isEmpty()
                    || answerTagsMatch(extractAnswerTags(referencedAnswer), rule.answerTagsAnyOf());
        }

        Set<String> answerOptionCodes = extractAnswerOptionCodes(referencedAnswer);
        Set<String> answerTags = extractAnswerTags(referencedAnswer);
        Set<String> compatibilityTags = deriveCompatibilityTags(rule.selectedOptionCodes());

        boolean optionMatch = !answerOptionCodes.isEmpty()
                && rule.selectedOptionCodes().stream().anyMatch(answerOptionCodes::contains);
        boolean explicitTagMatch = answerTagsMatch(answerTags, rule.answerTagsAnyOf());
        boolean compatibilityTagMatch = answerTagsMatch(answerTags, compatibilityTags);
        return optionMatch || explicitTagMatch || compatibilityTagMatch;
    }

    private SurveyQuestion resolveReferencedQuestion(BranchRule rule, SurveyQuestion currentQuestion, SessionContext context) {
        String fallbackGroupCode = null;
        if (rule.questionCode() != null) {
            SurveyQuestion directMatch = context.questionsByCode().get(rule.questionCode());
            if (directMatch != null) {
                return directMatch;
            }
            fallbackGroupCode = rule.questionCode();
        }

        String effectiveGroupCode = rule.groupCode() != null ? rule.groupCode() : fallbackGroupCode;
        if (effectiveGroupCode == null) {
            return null;
        }

        QuestionMetadata currentMetadata = context.metadataByQuestionId().get(currentQuestion.getId());
        String effectiveRowCode = rule.sameRowCode() && currentMetadata != null ? currentMetadata.rowCode() : rule.rowCode();
        for (SurveyQuestion candidate : context.questions()) {
            QuestionMetadata candidateMetadata = context.metadataByQuestionId().get(candidate.getId());
            if (candidateMetadata == null || !effectiveGroupCode.equals(candidateMetadata.groupCode())) {
                continue;
            }
            if (effectiveRowCode != null && !effectiveRowCode.equals(candidateMetadata.rowCode())) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private Set<String> extractAnswerOptionCodes(SurveyAnswer answer) {
        Set<String> values = new LinkedHashSet<>();
        if (answer.getSelectedOption() != null) {
            addOptionIdentifiers(values, answer.getSelectedOption());
        }
        String answerJson = trimToNull(answer.getAnswerJson());
        if (answerJson != null) {
            try {
                JsonNode root = objectMapper.readTree(answerJson);
                JsonNode normalizedValues = root.get("normalizedValues");
                if (normalizedValues != null && normalizedValues.isArray()) {
                    normalizedValues.forEach(item -> {
                        String value = trimToNull(item.asText(null));
                        if (value != null) {
                            values.add(normalize(value));
                        }
                    });
                }
            } catch (Exception ignored) {
                return values;
            }
        }
        return values;
    }

    private Set<String> extractAnswerTags(NormalizedAnswer normalizedAnswer) {
        Set<String> tags = new LinkedHashSet<>();
        if (normalizedAnswer.selectedOption() != null) {
            addSemanticTags(tags, normalizedAnswer.selectedOption().getLabel());
            addSemanticTags(tags, normalizedAnswer.selectedOption().getOptionCode());
            addSemanticTags(tags, normalizedAnswer.selectedOption().getValue());
        }
        normalizedAnswer.normalizedValues().forEach(value -> addSemanticTags(tags, value));
        addSemanticTags(tags, normalizedAnswer.answerText());
        return tags;
    }

    private Set<String> extractAnswerTags(SurveyAnswer answer) {
        Set<String> tags = new LinkedHashSet<>();
        if (answer.getSelectedOption() != null) {
            addSemanticTags(tags, answer.getSelectedOption().getLabel());
            addSemanticTags(tags, answer.getSelectedOption().getOptionCode());
            addSemanticTags(tags, answer.getSelectedOption().getValue());
        }
        String answerJson = trimToNull(answer.getAnswerJson());
        if (answerJson == null) {
            return tags;
        }
        try {
            JsonNode root = objectMapper.readTree(answerJson);
            JsonNode answerTagsNode = root.get("answerTags");
            if (answerTagsNode != null && answerTagsNode.isArray()) {
                answerTagsNode.forEach(item -> {
                    String value = normalizeOrNull(item.asText(null));
                    if (value != null) {
                        tags.add(value);
                    }
                });
            }
            JsonNode normalizedValues = root.get("normalizedValues");
            if (normalizedValues != null && normalizedValues.isArray()) {
                normalizedValues.forEach(item -> addSemanticTags(tags, item.asText(null)));
            }
        } catch (Exception ignored) {
            return tags;
        }
        return tags;
    }

    private boolean answerTagsMatch(Set<String> answerTags, List<String> ruleTags) {
        return !answerTags.isEmpty()
                && ruleTags != null
                && !ruleTags.isEmpty()
                && ruleTags.stream().anyMatch(answerTags::contains);
    }

    private boolean answerTagsMatch(Set<String> answerTags, Set<String> ruleTags) {
        return !answerTags.isEmpty()
                && ruleTags != null
                && !ruleTags.isEmpty()
                && ruleTags.stream().anyMatch(answerTags::contains);
    }

    private Set<String> deriveCompatibilityTags(List<String> selectedOptionCodes) {
        Set<String> tags = new LinkedHashSet<>();
        if (selectedOptionCodes == null) {
            return tags;
        }
        selectedOptionCodes.forEach(value -> addSemanticTags(tags, value));
        return tags;
    }

    private void addSemanticTags(Set<String> tags, String value) {
        String normalized = normalizeOrNull(value);
        if (normalized == null) {
            return;
        }
        if (normalized.contains("hic duymad")) {
            tags.add("knowledge_negative");
            tags.add("knowledge_never_heard");
        }
        if (normalized.contains("duydum") && normalized.contains("tanimiyorum")) {
            tags.add("knowledge_negative");
            tags.add("knowledge_heard_but_unknown");
        }
        if ((normalized.contains("tanimiyorum")
                || normalized.contains("bilmiyorum")
                || normalized.contains("tanimam"))
                && !normalized.contains("taniyorum")) {
            tags.add("knowledge_negative");
        }
        if ((normalized.contains("taniyorum") || normalized.contains("biliyorum"))
                && !normalized.contains("tanimiyorum")) {
            tags.add("knowledge_positive");
        }
        for (String token : normalized.split("\\s+")) {
            if (YES_WORDS.contains(token)) {
                tags.add("yes");
            }
            if (NO_WORDS.contains(token)) {
                tags.add("no");
            }
        }
    }

    private void addOptionIdentifiers(Set<String> values, SurveyQuestionOption option) {
        String code = trimToNull(option.getOptionCode());
        if (code != null) {
            values.add(normalize(code));
        }
        String rawValue = trimToNull(option.getValue());
        if (rawValue != null) {
            values.add(normalize(rawValue));
        }
        String label = trimToNull(option.getLabel());
        if (label != null) {
            values.add(normalize(label));
        }
    }

    private QuestionMetadata extractQuestionMetadata(SurveyQuestion question) {
        if (question.getSettingsJson() == null || question.getSettingsJson().isBlank()) {
            return new QuestionMetadata(null, null, null, null, null, null);
        }
        try {
            JsonNode root = objectMapper.readTree(question.getSettingsJson());
            return new QuestionMetadata(
                    normalizeOrNull(root.path("groupCode").asText(null)),
                    normalizeOrNull(root.path("rowCode").asText(null)),
                    trimToNull(root.path("groupTitle").asText(null)),
                    trimToNull(root.path("rowLabel").asText(null)),
                    trimToNull(root.path("matrixType").asText(null)),
                    trimToNull(root.path("matrixDescription").asText(null))
            );
        } catch (Exception ignored) {
            return new QuestionMetadata(null, null, null, null, null, null);
        }
    }

    private QuestionMetadata findPreviousQuestionMetadata(
            List<SurveyQuestion> eligibleQuestions,
            SurveyQuestion currentQuestion,
            SessionContext context
    ) {
        for (int index = 0; index < eligibleQuestions.size(); index += 1) {
            SurveyQuestion question = eligibleQuestions.get(index);
            if (!question.getId().equals(currentQuestion.getId())) {
                continue;
            }
            if (index == 0) {
                return null;
            }
            return context.metadataByQuestionId().get(eligibleQuestions.get(index - 1).getId());
        }
        return null;
    }

    private BranchConditionSet parseBranchConditionSet(String branchConditionJson) {
        String payload = trimToNull(branchConditionJson);
        if (payload == null || "{}".equals(payload)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root.isObject() && root.isEmpty()) {
                return null;
            }

            if (root.has("skipIf")) {
                return new BranchConditionSet(
                        BranchMode.SKIP_IF,
                        parseBranchOperator(root.path("operator").asText(null)),
                        parseBranchRules(root.get("skipIf"))
                );
            }
            if (root.has("askIf")) {
                return new BranchConditionSet(
                        BranchMode.ASK_IF,
                        parseBranchOperator(root.path("operator").asText(null)),
                        parseBranchRules(root.get("askIf"))
                );
            }
            if (root.has("rules")) {
                return new BranchConditionSet(
                        parseBranchMode(root.path("mode").asText(null)),
                        parseBranchOperator(root.path("operator").asText(null)),
                        parseBranchRules(root.get("rules"))
                );
            }
            return new BranchConditionSet(BranchMode.ASK_IF, BranchOperator.ALL, parseBranchRules(root));
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<BranchRule> parseBranchRules(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<BranchRule> rules = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> {
                BranchRule rule = parseBranchRule(item);
                if (rule != null) {
                    rules.add(rule);
                }
            });
            return List.copyOf(rules);
        }
        BranchRule singleRule = parseBranchRule(node);
        return singleRule == null ? List.of() : List.of(singleRule);
    }

    private BranchRule parseBranchRule(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<String> selectedOptionCodes = new ArrayList<>();
        JsonNode selectedOptionsNode = node.get("selectedOptionCodes");
        if (selectedOptionsNode != null && selectedOptionsNode.isArray()) {
            selectedOptionsNode.forEach(item -> {
                String value = trimToNull(item.asText(null));
                if (value != null) {
                    selectedOptionCodes.add(normalize(value));
                }
            });
        }
        List<String> answerTagsAnyOf = new ArrayList<>();
        JsonNode answerTagsNode = node.get("answerTagsAnyOf");
        if (answerTagsNode != null && answerTagsNode.isArray()) {
            answerTagsNode.forEach(item -> {
                String value = normalizeOrNull(item.asText(null));
                if (value != null) {
                    answerTagsAnyOf.add(value);
                }
            });
        }
        return new BranchRule(
                normalizeOrNull(node.path("questionCode").asText(null)),
                normalizeOrNull(node.path("groupCode").asText(null)),
                normalizeOrNull(node.path("rowCode").asText(null)),
                node.path("sameRowCode").asBoolean(false),
                List.copyOf(selectedOptionCodes),
                List.copyOf(answerTagsAnyOf),
                !node.has("validAnswerRequired") || node.path("validAnswerRequired").asBoolean(true)
        );
    }

    private BranchMode parseBranchMode(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return BranchMode.ASK_IF;
        }
        return "SKIP_IF".equalsIgnoreCase(rawMode.trim()) ? BranchMode.SKIP_IF : BranchMode.ASK_IF;
    }

    private BranchOperator parseBranchOperator(String rawOperator) {
        if (rawOperator == null || rawOperator.isBlank()) {
            return BranchOperator.ALL;
        }
        return "ANY".equalsIgnoreCase(rawOperator.trim()) ? BranchOperator.ANY : BranchOperator.ALL;
    }

    private List<String> extractOpenEndedCodes(SurveyQuestion question, String rawText) {
        String sanitizedText = sanitizeUtterance(rawText);
        if (sanitizedText == null || question.getSettingsJson() == null || question.getSettingsJson().isBlank()) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(question.getSettingsJson());
            JsonNode categoriesNode = root.path("coding").path("categories");
            if (categoriesNode.isMissingNode() || categoriesNode.isNull() || !categoriesNode.isObject()) {
                categoriesNode = root.path("codingCategories");
            }
            if (categoriesNode.isMissingNode() || categoriesNode.isNull() || !categoriesNode.isObject()) {
                return List.of();
            }

            String normalizedText = normalize(sanitizedText);
            List<String> matches = new ArrayList<>();
            categoriesNode.fields().forEachRemaining(entry -> {
                String categoryCode = normalizeOrNull(entry.getKey());
                if (categoryCode == null || !entry.getValue().isArray()) {
                    return;
                }

                boolean matched = false;
                for (JsonNode aliasNode : entry.getValue()) {
                    String alias = normalizeOrNull(aliasNode.asText(null));
                    if (alias == null) {
                        continue;
                    }
                    if (normalizedText.contains(alias)) {
                        matched = true;
                        break;
                    }
                }

                if (matched && !matches.contains(categoryCode)) {
                    matches.add(categoryCode);
                }
            });
            return List.copyOf(matches);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private InterviewQuestionPayload toQuestionPayload(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            SurveyAnswer answer,
            int maxRetryCount,
            QuestionMetadata currentMetadata,
            QuestionMetadata previousMetadata
    ) {
        Integer ratingMin = question.getQuestionType() == QuestionType.RATING ? extractRatingMin(question) : null;
        Integer ratingMax = question.getQuestionType() == QuestionType.RATING ? extractRatingMax(question) : null;
        int retryCount = answer == null || answer.getRetryCount() == null ? 0 : answer.getRetryCount();
        String spokenPrompt = buildQuestionPrompt(question, options, ratingMin, ratingMax, currentMetadata, previousMetadata);
        String clarificationPrompt = buildRetryPrompt(question, options, ratingMin, ratingMax);
        return new InterviewQuestionPayload(
                question.getId(),
                question.getCode(),
                question.getQuestionOrder(),
                question.getTitle(),
                question.getDescription(),
                question.getQuestionType(),
                resolveConversationQuestionType(question, options),
                question.isRequired(),
                answer != null && !answer.isValid() && retryCount > 0,
                retryCount,
                maxRetryCount,
                ratingMin,
                ratingMax,
                options.stream()
                        .map(option -> new InterviewQuestionOptionPayload(option.getId(), option.getOptionCode(), option.getLabel(), option.getValue()))
                        .toList(),
                spokenPrompt,
                clarificationPrompt
        );
    }

    private String resolveConversationQuestionType(SurveyQuestion question, List<SurveyQuestionOption> options) {
        if (question.getQuestionType() == QuestionType.SINGLE_CHOICE && looksLikeYesNo(options)) {
            return "YES_NO";
        }
        return question.getQuestionType().name();
    }

    private String buildQuestionPrompt(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            Integer ratingMin,
            Integer ratingMax,
            QuestionMetadata currentMetadata,
            QuestionMetadata previousMetadata
    ) {
        boolean turkish = isTurkish(question.getSurvey());
        String matrixPrompt = buildMatrixQuestionPrompt(question, currentMetadata, previousMetadata, turkish, ratingMin, ratingMax);
        if (matrixPrompt != null) {
            return matrixPrompt;
        }
        StringBuilder builder = new StringBuilder();
        appendPromptSentence(builder, stripInlineChoiceListFromPrompt(question, options));
        if (question.getDescription() != null && !question.getDescription().isBlank()) {
            appendPromptSentence(builder, question.getDescription());
        }
        if (question.getQuestionType() == QuestionType.RATING && ratingMin != null && ratingMax != null) {
            if (turkish) {
                appendPromptSentence(builder, "Lütfen " + ratingMin + " ile " + ratingMax + " arasında tek bir sayı söyleyin");
            } else {
                appendPromptSentence(builder, "Please answer with one number between " + ratingMin + " and " + ratingMax);
            }
        }
        if (question.getQuestionType() == QuestionType.NUMBER) {
            if (turkish) {
                appendPromptSentence(builder, "Lutfen tek bir sayi soyleyin");
            } else {
                appendPromptSentence(builder, "Please answer with one number");
            }
        }
        return normalizePromptPacing(builder.toString());
    }

    private String stripInlineChoiceListFromPrompt(SurveyQuestion question, List<SurveyQuestionOption> options) {
        String title = trimToNull(question.getTitle());
        if (title == null || options == null || options.size() < 3) {
            return question.getTitle();
        }
        if (question.getQuestionType() != QuestionType.SINGLE_CHOICE && question.getQuestionType() != QuestionType.MULTI_CHOICE) {
            return title;
        }

        int questionMarkIndex = title.indexOf('?');
        if (questionMarkIndex <= 0 || questionMarkIndex >= title.length() - 1) {
            return title;
        }

        String suffix = title.substring(questionMarkIndex + 1);
        if (countInlineChoiceMatches(suffix, options) < 3) {
            return title;
        }
        return title.substring(0, questionMarkIndex + 1).trim();
    }

    private long countInlineChoiceMatches(String text, List<SurveyQuestionOption> options) {
        String normalizedText = normalize(text);
        return options.stream()
                .map(SurveyQuestionOption::getLabel)
                .filter(label -> inlineChoiceLabelMatches(normalizedText, label))
                .count();
    }

    private boolean inlineChoiceLabelMatches(String normalizedText, String label) {
        String normalizedLabel = normalize(label);
        if (normalizedLabel.isBlank()) {
            return false;
        }
        if (normalizedText.contains(normalizedLabel)) {
            return true;
        }
        return semanticTokens(normalizedLabel).stream()
                .filter(token -> token.length() >= 3)
                .anyMatch(normalizedText::contains);
    }

    private String buildMatrixQuestionPrompt(
            SurveyQuestion question,
            QuestionMetadata currentMetadata,
            QuestionMetadata previousMetadata,
            boolean turkish,
            Integer ratingMin,
            Integer ratingMax
    ) {
        if (currentMetadata == null || currentMetadata.groupCode() == null || currentMetadata.rowLabel() == null) {
            return null;
        }
        if (currentMetadata.matrixType() == null || !currentMetadata.matrixType().startsWith("GRID_")) {
            return null;
        }

        boolean firstInGroup = previousMetadata == null
                || previousMetadata.groupCode() == null
                || !currentMetadata.groupCode().equals(previousMetadata.groupCode());
        StringBuilder builder = new StringBuilder();
        if (firstInGroup && currentMetadata.groupTitle() != null) {
            appendPromptSentence(builder, currentMetadata.groupTitle());
        }
        appendPromptSentence(builder, trimRepeatedMatrixDescription(currentMetadata.rowLabel(), currentMetadata.matrixDescription()));
        if (firstInGroup && currentMetadata.matrixDescription() != null) {
            appendPromptSentence(builder, currentMetadata.matrixDescription());
        }
        if (question.getQuestionType() == QuestionType.RATING && ratingMin != null && ratingMax != null) {
            if (turkish) {
                appendPromptSentence(builder, "Lütfen " + ratingMin + " ile " + ratingMax + " arasında tek bir sayı söyleyin");
            } else {
                appendPromptSentence(builder, "Please answer with one number between " + ratingMin + " and " + ratingMax);
            }
        }
        return normalizePromptPacing(builder.toString());
    }

    private String trimRepeatedMatrixDescription(String rowLabel, String matrixDescription) {
        String effectiveRowLabel = trimToNull(rowLabel);
        String effectiveDescription = trimToNull(matrixDescription);
        if (effectiveRowLabel == null || effectiveDescription == null) {
            return effectiveRowLabel;
        }

        String normalizedRowLabel = normalize(effectiveRowLabel);
        String normalizedDescription = normalize(effectiveDescription);
        if (normalizedRowLabel.isBlank() || normalizedDescription.isBlank() || !normalizedRowLabel.endsWith(normalizedDescription)) {
            return effectiveRowLabel;
        }

        String loweredRowLabel = effectiveRowLabel.toLowerCase(Locale.ROOT);
        String loweredDescription = effectiveDescription.toLowerCase(Locale.ROOT);
        int repeatedDescriptionIndex = loweredRowLabel.lastIndexOf(loweredDescription);
        if (repeatedDescriptionIndex <= 0) {
            return effectiveRowLabel;
        }

        String trimmedPrefix = trimToNull(effectiveRowLabel.substring(0, repeatedDescriptionIndex).replaceFirst("[\\s,;:()\\-]+$", ""));
        return trimmedPrefix != null ? trimmedPrefix : effectiveRowLabel;
    }

    private String buildRetryPrompt(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            Integer ratingMin,
            Integer ratingMax
    ) {
        if (question.getRetryPrompt() != null && !question.getRetryPrompt().isBlank()) {
            return question.getRetryPrompt().trim();
        }
        boolean turkish = isTurkish(question.getSurvey());
        if ((question.getQuestionType() == QuestionType.SINGLE_CHOICE || question.getQuestionType() == QuestionType.MULTI_CHOICE)
                && !options.isEmpty()) {
            if (turkish) {
                return "Sizi net duyamadim. Tekrarlar misiniz?";
            }
            return "I could not hear that clearly. Could you repeat that?";
        }
        if (question.getQuestionType() == QuestionType.RATING && ratingMin != null && ratingMax != null) {
            if (turkish) {
                return "Yaniti puan olarak anlayamadim. Lutfen "
                        + ratingMin + " ile " + ratingMax + " arasinda tek bir sayi soyleyin.";
            }
            return "I could not convert that into a rating. Please say one number between "
                    + ratingMin + " and " + ratingMax + ".";
        }
        if (question.getQuestionType() == QuestionType.NUMBER) {
            if (turkish) {
                return "Yaniti sayi olarak anlayamadim. Lutfen tek bir sayi soyleyin.";
            }
            return "I could not convert that into a number. Please say one number.";
        }
        if (turkish) {
            return "Sizi net duyamadim. Lutfen bir kez daha cevaplayin.";
        }
        return "I could not hear that clearly. Please answer once more.";
    }

    private String buildRetryLeadIn(SurveyQuestion question, SurveyAnswer answer) {
        String clarificationPrompt = extractClarificationPrompt(answer);
        if (clarificationPrompt != null) {
            return clarificationPrompt;
        }
        if (answer.getInvalidReason() != null && !answer.getInvalidReason().isBlank()) {
            String invalidReason = localizeInvalidReason(question.getSurvey(), answer.getInvalidReason());
            return invalidReason + ". " + buildRetryPrompt(
                    question,
                    List.of(),
                    extractRatingMin(question),
                    extractRatingMax(question)
            );
        }
        return buildRetryPrompt(question, List.of(), extractRatingMin(question), extractRatingMax(question));
    }

    private void appendPromptSentence(StringBuilder builder, String text) {
        String cleaned = trimToNull(text);
        if (cleaned == null) {
            return;
        }
        String value = cleaned.trim();
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value);
        char lastChar = value.charAt(value.length() - 1);
        if (lastChar != '.' && lastChar != '!' && lastChar != '?') {
            builder.append('.');
        }
    }

    private String normalizePromptPacing(String prompt) {
        String cleaned = trimToNull(prompt);
        if (cleaned == null) {
            return "";
        }
        return cleaned
                .replace(" LÃ¼tfen ", ". LÃ¼tfen ")
                .replace("? LÃ¼tfen", "? Bir de")
                .replace(" Please answer ", ". Please answer ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractClarificationPrompt(SurveyAnswer answer) {
        String answerJson = trimToNull(answer.getAnswerJson());
        if (answerJson == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(answerJson);
            return trimToNull(root.path("clarificationPrompt").asText(null));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildClosingPrompt(Survey survey) {
        if (survey.getClosingPrompt() != null && !survey.getClosingPrompt().isBlank()) {
            return survey.getClosingPrompt().trim();
        }
        if (isTurkish(survey)) {
            return "Vakit ayırdığınız için teşekkür ederim. Anketimiz burada sona erdi. İyi günler dilerim.";
        }
        return "Thank you for your time. The survey is now complete. Goodbye.";
    }

    private String buildOpeningLeadIn(SessionContext context) {
        String surveyIntro = trimToNull(context.survey().getIntroPrompt());
        if (surveyIntro != null) {
            return surveyIntro;
        }
        return null;
    }

    private boolean requiresOpeningGreeting(Survey survey) {
        return trimToNull(survey.getIntroPrompt()) != null;
    }

    private boolean isOpeningGreetingLike(InterviewAnswerRequest request) {
        InterviewConversationSignal signal = request.signal() == null ? InterviewConversationSignal.ANSWER : request.signal();
        if (signal == InterviewConversationSignal.NO_INPUT) {
            return false;
        }
        String rawInput = trimToNull(request.utteranceText());
        String sanitizedInput = sanitizeUtterance(rawInput);
        if (sanitizedInput == null || isLikelyAsrArtifact(rawInput, sanitizedInput)) {
            return false;
        }
        return true;
    }

    // A submission for a question that is no longer current, or the same utterance again within a few
    // seconds, comes from a retried or doubled tool call. Applying it would answer the next question.
    // A genuine next answer cannot arrive that fast, because the agent first has to ask the question.
    private boolean isStaleOrRepeatedSubmission(InterviewAnswerRequest request, SurveyResponse response, SurveyQuestion currentQuestion) {
        if (request.questionId() != null && !request.questionId().equals(currentQuestion.getId())) {
            return true;
        }
        String utterance = normalize(request.utteranceText());
        if (utterance.isBlank()) {
            return false;
        }
        try {
            JsonNode lastSubmission = readTranscriptJson(response.getTranscriptJson()).path(LAST_ANSWER_SUBMISSION_KEY);
            return lastSubmission.isObject()
                    && utterance.equals(lastSubmission.path("text").asText(null))
                    && System.currentTimeMillis() - lastSubmission.path("receivedAtEpochMillis").asLong(0)
                            <= REPEATED_SUBMISSION_WINDOW.toMillis();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void recordAnswerSubmission(SurveyResponse response, String utteranceText) {
        try {
            JsonNode root = readTranscriptJson(response.getTranscriptJson());
            Map<String, Object> payload = objectMapper.convertValue(root, Map.class);
            Map<String, Object> submission = new LinkedHashMap<>();
            submission.put("text", normalize(utteranceText));
            submission.put("receivedAtEpochMillis", System.currentTimeMillis());
            payload.put(LAST_ANSWER_SUBMISSION_KEY, submission);
            response.setTranscriptJson(objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            // Duplicate detection is best effort; the row lock already serializes the tool calls.
        }
    }

    private boolean isOpeningGreetingReceived(SurveyResponse response) {
        try {
            JsonNode root = readTranscriptJson(response.getTranscriptJson());
            return root.path(GREETING_RECEIVED_KEY).asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateOpeningGreetingReceived(SurveyResponse response, boolean received) {
        try {
            JsonNode root = readTranscriptJson(response.getTranscriptJson());
            Map<String, Object> payload = objectMapper.convertValue(root, Map.class);
            payload.put(GREETING_RECEIVED_KEY, received);
            response.setTranscriptJson(objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            response.setTranscriptJson("{\"" + GREETING_RECEIVED_KEY + "\":" + received + "}");
        }
    }

    private void initializeOpeningConsentState(SurveyResponse response, Survey survey) {
        if (requiresOpeningConsent(survey) && readOpeningConsentState(response) == null) {
            updateOpeningConsentState(response, CONSENT_PENDING);
        }
    }

    private boolean isOpeningConsentPending(Survey survey, SurveyResponse response) {
        return requiresOpeningConsent(survey) && CONSENT_PENDING.equals(readOpeningConsentState(response));
    }

    private boolean requiresOpeningConsent(Survey survey) {
        String introPrompt = trimToNull(survey.getIntroPrompt());
        if (introPrompt == null) {
            return false;
        }
        String normalized = normalize(introPrompt);
        boolean containsSurveyParticipationIntent = normalized.contains("anket")
                && (normalized.contains("katil") || normalized.contains("katilir") || normalized.contains("katilim"));
        return introPrompt.contains("?")
                || normalized.contains("sorabilir miyim")
                || normalized.contains("birkac soru sorabilir miyim")
                || normalized.contains("uygun musunuz")
                || normalized.contains("uygun musun")
                || normalized.contains("izin verir misiniz")
                || normalized.contains("izin verir misin")
                || normalized.contains("katilmak ister misiniz")
                || normalized.contains("katilmak ister misin")
                || normalized.contains("katilir misiniz")
                || normalized.contains("katilir misin")
                || normalized.contains("may i ask")
                || normalized.contains("can i ask")
                || normalized.contains("is this a good time")
                || normalized.contains("do you have a minute")
                || containsSurveyParticipationIntent;
    }

    // Matches whole words, so "olur" does not match inside "olursunuz". A refusal phrase wins over an
    // acceptance phrase ("evet ama simdi olmaz"). A bare "hayir" or "yok" stays unclear: whether it
    // refuses depends on how the intro asks ("sorabilir miyim?" or "rahatsiz ediyor muyum?").
    private ConsentDecision classifyOpeningConsentDecision(String rawText) {
        String normalized = normalize(rawText);
        if (normalized.isBlank()) {
            return ConsentDecision.UNCLEAR;
        }
        if (CONSENT_DECLINE_PHRASES.stream().anyMatch(phrase -> containsWords(normalized, phrase))) {
            return ConsentDecision.DECLINED;
        }
        if (CONSENT_ACCEPT_PHRASES.stream().anyMatch(phrase -> containsWords(normalized, phrase))) {
            return ConsentDecision.ACCEPTED;
        }
        List<String> tokens = List.of(normalized.split("\\s+"));
        if (tokens.stream().anyMatch(YES_WORDS::contains)) {
            return ConsentDecision.ACCEPTED;
        }
        return ConsentDecision.UNCLEAR;
    }

    private int readConsentClarificationCount(SurveyResponse response) {
        try {
            return readTranscriptJson(response.getTranscriptJson()).path(CONSENT_CLARIFICATION_COUNT_KEY).asInt(0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void updateConsentClarificationCount(SurveyResponse response, int count) {
        try {
            JsonNode root = readTranscriptJson(response.getTranscriptJson());
            Map<String, Object> payload = objectMapper.convertValue(root, Map.class);
            payload.put(CONSENT_CLARIFICATION_COUNT_KEY, count);
            response.setTranscriptJson(objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            response.setTranscriptJson("{\"" + CONSENT_CLARIFICATION_COUNT_KEY + "\":" + count + "}");
        }
    }

    private boolean containsAnyPhrase(String normalizedText, String... phrases) {
        for (String phrase : phrases) {
            if (normalizedText.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private String readOpeningConsentState(SurveyResponse response) {
        try {
            JsonNode root = readTranscriptJson(response.getTranscriptJson());
            if (root.hasNonNull(CONSENT_STATE_KEY)) {
                return trimToNull(root.get(CONSENT_STATE_KEY).asText());
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private boolean isOpeningConsentPromptDelivered(SurveyResponse response) {
        try {
            JsonNode root = readTranscriptJson(response.getTranscriptJson());
            return root.path(CONSENT_PROMPT_DELIVERED_KEY).asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateOpeningConsentState(SurveyResponse response, String state) {
        try {
            JsonNode root = readTranscriptJson(response.getTranscriptJson());
            Map<String, Object> payload = objectMapper.convertValue(root, Map.class);
            payload.put(CONSENT_STATE_KEY, state);
            response.setTranscriptJson(objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            response.setTranscriptJson("{\"" + CONSENT_STATE_KEY + "\":\"" + state + "\"}");
        }
    }

    private void updateOpeningConsentPromptDelivered(SurveyResponse response, boolean delivered) {
        try {
            JsonNode root = readTranscriptJson(response.getTranscriptJson());
            Map<String, Object> payload = objectMapper.convertValue(root, Map.class);
            payload.put(CONSENT_PROMPT_DELIVERED_KEY, delivered);
            response.setTranscriptJson(objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            response.setTranscriptJson("{\"" + CONSENT_PROMPT_DELIVERED_KEY + "\":" + delivered + "}");
        }
    }

    private JsonNode readTranscriptJson(String payload) throws JsonProcessingException {
        String effectivePayload = trimToNull(payload);
        return objectMapper.readTree(effectivePayload == null ? "{}" : effectivePayload);
    }

    private String buildOpeningConsentRepeatPrompt(Survey survey) {
        String introPrompt = trimToNull(survey.getIntroPrompt());
        if (introPrompt != null) {
            return introPrompt;
        }
        return "Please let me know if I may continue with the survey.";
    }

    private String buildOpeningConsentClarificationPrompt(Survey survey) {
        String introPrompt = trimToNull(survey.getIntroPrompt());
        if (introPrompt != null) {
            return localized(survey, "Kısaca onayınızı rica edeceğim. " + introPrompt, "Before we continue, may I briefly ask for your consent? " + introPrompt);
        }
        return "May I continue with the survey?";
    }

    private String buildOpeningConsentIdentityPrompt(SessionContext context) {
        String introPrompt = trimToNull(context.survey().getIntroPrompt());
        String companyName = resolveCallerCompanyName(context);
        String operationName = trimToNull(context.callAttempt().getOperation().getName());
        String explanation = isTurkish(context.survey())
                ? buildTurkishIdentityPrompt(companyName, operationName, null)
                : buildEnglishIdentityPrompt(companyName, operationName, null);
        if (introPrompt != null) {
            return explanation + " " + introPrompt;
        }
        return explanation + localized(context.survey(), " Sizinle kısa bir anket yapabilir miyim?", " May I conduct a short survey with you?");
    }

    private String buildInSurveyIdentityPrompt(SessionContext context) {
        String companyName = resolveCallerCompanyName(context);
        String operationName = trimToNull(context.callAttempt().getOperation().getName());
        String surveyName = trimToNull(context.survey().getName());

        if (isTurkish(context.survey())) {
            return buildTurkishIdentityPrompt(companyName, operationName, surveyName);
        }

        return buildEnglishIdentityPrompt(companyName, operationName, surveyName);
    }

    // The callee hears the name of the research company that owns the operation (the tenant),
    // not the SurveyAI platform.
    private String resolveCallerCompanyName(SessionContext context) {
        Company company = context.callAttempt().getOperation().getCompany();
        return company == null ? null : trimToNull(company.getName());
    }

    private String buildTurkishIdentityPrompt(String companyName, String operationName, String surveyName) {
        String caller = companyName != null ? "Ben " + companyName + " adina " : "Ben ";
        if (operationName != null && surveyName != null) {
            return caller + operationName
                    + " kapsamindaki " + surveyName
                    + " anketi icin ariyorum.";
        }
        if (operationName != null) {
            return caller + operationName + " arastirmasi icin ariyorum.";
        }
        if (surveyName != null) {
            return caller + surveyName + " anketi icin ariyorum.";
        }
        return caller + "kisa bir anket calismasi icin ariyorum.";
    }

    private String buildEnglishIdentityPrompt(String companyName, String operationName, String surveyName) {
        String caller = companyName != null ? "I am calling on behalf of " + companyName + " " : "I am calling ";
        if (operationName != null && surveyName != null) {
            return caller + "for the " + operationName
                    + " study and the " + surveyName + " survey.";
        }
        if (operationName != null) {
            return caller + "for the " + operationName + " study.";
        }
        if (surveyName != null) {
            return caller + "for the " + surveyName + " survey.";
        }
        return caller + "for a short survey study.";
    }

    private String buildConsentDeclinedClosingPrompt(Survey survey) {
        String surveyClosing = trimToNull(survey.getClosingPrompt());
        if (surveyClosing != null) {
            return surveyClosing;
        }
        return isTurkish(survey)
                ? "Anladım, teşekkür ederim. İyi günler dilerim."
                : "Understood, thank you for your time. Goodbye.";
    }

    private String buildConsentUnclearClosingPrompt(Survey survey) {
        String surveyClosing = trimToNull(survey.getClosingPrompt());
        if (surveyClosing != null) {
            return surveyClosing;
        }
        return isTurkish(survey)
                ? "Sizi şu an net anlayamadım, rahatsız ettiğim için kusura bakmayın. İyi günler dilerim."
                : "I could not hear you clearly, sorry for the trouble. Goodbye.";
    }

    private String buildConsentAcceptedLeadIn(Survey survey) {
        return localized(
                survey,
                "Teşekkür ederim.",
                "Thank you."
        );
    }

    private boolean isTerminalResponse(SurveyResponse response) {
        return response != null
                && (response.getStatus() == SurveyResponseStatus.COMPLETED
                || response.getStatus() == SurveyResponseStatus.ABANDONED);
    }

    private String resolveTerminalClosingPrompt(SessionContext context, SurveyResponse response) {
        String consentState = readOpeningConsentState(response);
        if (CONSENT_DECLINED.equals(consentState)) {
            return buildConsentDeclinedClosingPrompt(context.survey());
        }
        if (CONSENT_UNCLEAR.equals(consentState)) {
            return buildConsentUnclearClosingPrompt(context.survey());
        }
        return buildClosingPrompt(context.survey());
    }

    private String localizeInvalidReason(Survey survey, String invalidReason) {
        if (!isTurkish(survey)) {
            return invalidReason;
        }
        return switch (invalidReason) {
            case "No clear answer captured from the caller" -> "Söylediğinizi net anlayamadım";
            case "Answer did not match a known option" -> "Sizi net duyamadım";
            case "Answer did not match any known options" -> "Sizi net duyamadım";
            default -> invalidReason.startsWith("Rating answer must be between ")
                    ? invalidReason
                    : "Sizi net duyamadım";
        };
    }

    private String localized(Survey survey, String turkishText, String defaultText) {
        return isTurkish(survey) ? turkishText : defaultText;
    }

    private boolean isTurkish(Survey survey) {
        String languageCode = survey == null ? null : trimToNull(survey.getLanguageCode());
        return languageCode != null && languageCode.toLowerCase(Locale.ROOT).startsWith("tr");
    }

    private String buildContactName(CallAttempt callAttempt) {
        String firstName = callAttempt.getOperationContact().getFirstName() == null ? "" : callAttempt.getOperationContact().getFirstName().trim();
        String lastName = callAttempt.getOperationContact().getLastName() == null ? "" : callAttempt.getOperationContact().getLastName().trim();
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isBlank() ? "Valued participant" : fullName;
    }

    private Integer extractRatingMin(SurveyQuestion question) {
        return readIntegerSetting(question.getSettingsJson(), 1, "min", "scaleMin", "lowerBound");
    }

    private Integer extractRatingMax(SurveyQuestion question) {
        return readIntegerSetting(question.getSettingsJson(), 5, "max", "scaleMax", "upperBound");
    }

    private Integer readIntegerSetting(String settingsJson, int fallback, String... fields) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return fallback;
        }
        try {
            JsonNode root = objectMapper.readTree(settingsJson);
            for (String field : fields) {
                if (root.has(field) && root.get(field).canConvertToInt()) {
                    return root.get(field).asInt();
                }
            }
        } catch (Exception ignored) {
            return fallback;
        }
        return fallback;
    }

    private TurkeyGeoDataService.GeoScope extractGeoScope(SurveyQuestion question) {
        if (question.getSettingsJson() == null || question.getSettingsJson().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(question.getSettingsJson());
            JsonNode autoLexicon = root.path("autoLexicon");
            if (!autoLexicon.isObject() || !"geo".equalsIgnoreCase(autoLexicon.path("type").asText())) {
                return null;
            }

            String granularityValue = trimToNull(autoLexicon.path("granularity").asText(null));
            if (granularityValue == null) {
                return null;
            }

            TurkeyGeoDataService.GeoGranularity granularity = TurkeyGeoDataService.GeoGranularity.valueOf(granularityValue);
            Set<String> cityCodes = new LinkedHashSet<>();
            JsonNode cityCodesNode = autoLexicon.get("cityCodes");
            if (cityCodesNode != null && cityCodesNode.isArray()) {
                cityCodesNode.forEach(item -> {
                    String value = trimToNull(item.asText(null));
                    if (value != null) {
                        cityCodes.add(value);
                    }
                });
            }

            Set<String> districtNames = new LinkedHashSet<>();
            JsonNode districtNamesNode = autoLexicon.get("districtNames");
            if (districtNamesNode != null && districtNamesNode.isArray()) {
                districtNamesNode.forEach(item -> {
                    String value = trimToNull(item.asText(null));
                    if (value != null) {
                        districtNames.add(value);
                    }
                });
            }

            return new TurkeyGeoDataService.GeoScope(granularity, Set.copyOf(cityCodes), Set.copyOf(districtNames));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lowered = value.trim().toLowerCase(Locale.ROOT)
                .replace('\u0131', 'i')
                .replace('\u0130', 'i')
                .replace('\u015f', 's')
                .replace('\u015e', 's')
                .replace('\u011f', 'g')
                .replace('\u011e', 'g')
                .replace('\u00fc', 'u')
                .replace('\u00dc', 'u')
                .replace('\u00f6', 'o')
                .replace('\u00d6', 'o')
                .replace('\u00e7', 'c')
                .replace('\u00c7', 'c')
                .replace('ı', 'i')
                .replace('İ', 'i')
                .replace('ş', 's')
                .replace('Ş', 's')
                .replace('ğ', 'g')
                .replace('Ğ', 'g')
                .replace('ü', 'u')
                .replace('Ü', 'u')
                .replace('ö', 'o')
                .replace('Ö', 'o')
                .replace('ç', 'c')
                .replace('Ç', 'c');
        String decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private String normalizeOrNull(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String normalized = normalize(trimmed);
        return normalized.isBlank() ? null : normalized;
    }

    private String sanitizeUtterance(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String withoutMarkup = TRANSCRIPT_MARKUP_PATTERN.matcher(trimmed).replaceAll(" ");
        return trimToNull(withoutMarkup.replaceAll("\\s+", " "));
    }

    private boolean isLikelyAsrArtifact(String originalValue, String sanitizedValue) {
        if (originalValue == null || sanitizedValue == null) {
            return false;
        }
        if (!TRANSCRIPT_MARKUP_PATTERN.matcher(originalValue).find()) {
            return false;
        }

        String normalized = normalize(sanitizedValue);
        if (normalized.isBlank()) {
            return true;
        }

        String[] tokens = normalized.split("\\s+");
        return tokens.length <= 1;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String sanitizePromptForSpeech(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String withoutDirections = VOICE_DIRECTION_PATTERN.matcher(trimmed).replaceAll(" ");
        return trimToNull(withoutDirections.replaceAll("\\s+", " "));
    }

    private record SessionContext(
            CallAttempt callAttempt,
            Survey survey,
            List<SurveyQuestion> questions,
            Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId,
            Map<UUID, SurveyAnswer> answersByQuestionId,
            int maxRetryCount,
            Map<UUID, QuestionMetadata> metadataByQuestionId,
            Map<String, SurveyQuestion> questionsByCode
    ) {
    }

    private record QuestionCursor(
            SurveyQuestion question,
            int retryCount
    ) {
    }

    private record FlowState(
            List<SurveyQuestion> eligibleQuestions,
            QuestionCursor cursor,
            int completedRequiredQuestionCount,
            int totalRequiredQuestionCount
    ) {
    }

    private record NormalizedAnswer(
            boolean valid,
            String rawText,
            String answerText,
            BigDecimal numberValue,
            SurveyQuestionOption selectedOption,
            List<String> normalizedValues,
            String invalidReason,
            String specialAnswerCode,
            String clarificationPrompt
    ) {
        private static NormalizedAnswer validText(String rawText) {
            return new NormalizedAnswer(true, rawText, rawText, null, null, List.of(rawText), null, null, null);
        }

        private static NormalizedAnswer validText(String rawText, String answerText, List<String> normalizedValues) {
            return new NormalizedAnswer(true, rawText, answerText, null, null, normalizedValues, null, null, null);
        }

        private static NormalizedAnswer validNumber(String rawText, BigDecimal numberValue) {
            return new NormalizedAnswer(true, rawText, numberValue.toPlainString(), numberValue, null, List.of(numberValue.toPlainString()), null, null, null);
        }

        private static NormalizedAnswer validOption(String rawText, SurveyQuestionOption option, List<String> normalizedValues) {
            return new NormalizedAnswer(true, rawText, option.getLabel(), null, option, normalizedValues, null, null, null);
        }

        private static NormalizedAnswer validMulti(String rawText, List<String> normalizedValues) {
            return new NormalizedAnswer(true, rawText, String.join(", ", normalizedValues), null, null, normalizedValues, null, null, null);
        }

        private static NormalizedAnswer validSpecial(String rawText, String specialAnswerCode, String answerText) {
            return new NormalizedAnswer(true, rawText, answerText, null, null, List.of(specialAnswerCode), null, specialAnswerCode, null);
        }

        private static NormalizedAnswer invalid(String reason, String rawText) {
            return new NormalizedAnswer(false, rawText, rawText, null, null, List.of(), reason, null, null);
        }

        private static NormalizedAnswer invalidWithClarification(String reason, String rawText, String clarificationPrompt) {
            return new NormalizedAnswer(false, rawText, rawText, null, null, List.of(), reason, null, clarificationPrompt);
        }
    }

    private record SemanticOptionDecision(
            SurveyQuestionOption matchedOption,
            String clarificationPrompt
    ) {
    }

    private record AutoEntityEntry(
            String label,
            List<String> aliases
    ) {
    }

    private record AutoEntityScore(
            AutoEntityEntry entry,
            double score
    ) {
    }

    private record OptionSemanticScore(
            SurveyQuestionOption option,
            double score
    ) {
    }

    private record SpecialAnswerMatch(
            String code,
            String label
    ) {
    }

    private enum ConsentDecision {
        ACCEPTED,
        DECLINED,
        UNCLEAR
    }

    private record QuestionMetadata(
            String groupCode,
            String rowCode,
            String groupTitle,
            String rowLabel,
            String matrixType,
            String matrixDescription
    ) {
    }

    private record BranchConditionSet(
            BranchMode mode,
            BranchOperator operator,
            List<BranchRule> rules
    ) {
    }

    private record BranchRule(
            String questionCode,
            String groupCode,
            String rowCode,
            boolean sameRowCode,
            List<String> selectedOptionCodes,
            List<String> answerTagsAnyOf,
            boolean validAnswerRequired
    ) {
    }

    private enum BranchMode {
        ASK_IF,
        SKIP_IF
    }

    private enum BranchOperator {
        ALL,
        ANY
    }
}


