package com.yourcompany.surveyai.response.application.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookEvent;
import com.yourcompany.surveyai.call.application.service.ProviderExecutionObservationService;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.response.application.model.IngestedSurveyAnswer;
import com.yourcompany.surveyai.response.application.model.IngestedSurveyResult;
import com.yourcompany.surveyai.response.application.provider.ProviderSurveyResultMapper;
import com.yourcompany.surveyai.response.application.provider.ProviderSurveyResultMapperRegistry;
import com.yourcompany.surveyai.response.application.service.SurveyResponseIngestionService;
import com.yourcompany.surveyai.response.domain.entity.SurveyAnswer;
import com.yourcompany.surveyai.response.domain.entity.SurveyResponse;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import com.yourcompany.surveyai.response.repository.SurveyAnswerRepository;
import com.yourcompany.surveyai.response.repository.SurveyResponseRepository;
import com.yourcompany.surveyai.operation.support.OperationContactPhoneResolver;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionOptionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SurveyResponseIngestionServiceImpl implements SurveyResponseIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SurveyResponseIngestionServiceImpl.class);

    private final ProviderSurveyResultMapperRegistry mapperRegistry;
    private final SurveyResponseRepository surveyResponseRepository;
    private final SurveyAnswerRepository surveyAnswerRepository;
    private final SurveyQuestionRepository surveyQuestionRepository;
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository;
    private final ObjectMapper objectMapper;
    private final ProviderExecutionObservationService providerExecutionObservationService;

    public SurveyResponseIngestionServiceImpl(
            ProviderSurveyResultMapperRegistry mapperRegistry,
            SurveyResponseRepository surveyResponseRepository,
            SurveyAnswerRepository surveyAnswerRepository,
            SurveyQuestionRepository surveyQuestionRepository,
            SurveyQuestionOptionRepository surveyQuestionOptionRepository,
            ObjectMapper objectMapper,
            ProviderExecutionObservationService providerExecutionObservationService
    ) {
        this.mapperRegistry = mapperRegistry;
        this.surveyResponseRepository = surveyResponseRepository;
        this.surveyAnswerRepository = surveyAnswerRepository;
        this.surveyQuestionRepository = surveyQuestionRepository;
        this.surveyQuestionOptionRepository = surveyQuestionOptionRepository;
        this.objectMapper = objectMapper;
        this.providerExecutionObservationService = providerExecutionObservationService;
    }

    @Override
    @Transactional
    public void ingest(CallAttempt callAttempt, ProviderWebhookEvent event) {
        try {
            ProviderSurveyResultMapper mapper = mapperRegistry.getRequiredMapper(callAttempt.getProvider());
            IngestedSurveyResult mappedResult = mapper.map(callAttempt, event);

            SurveyResponse response = surveyResponseRepository.findByCallAttempt_IdAndDeletedAtIsNull(callAttempt.getId())
                    .orElseGet(() -> createSurveyResponse(callAttempt));

            if (isOutcomeDecided(response)) {
                attachTranscript(callAttempt, event, response, mappedResult);
                return;
            }

            response.setStatus(mappedResult.responseStatus());
            response.setCompletionPercent(defaultCompletion(mappedResult.completionPercent()));
            response.setStartedAt(firstNonNull(mappedResult.startedAt(), callAttempt.getConnectedAt(), callAttempt.getDialedAt(), OffsetDateTime.now()));
            response.setCompletedAt(mappedResult.completedAt());
            response.setTranscriptText(mappedResult.transcriptText());
            response.setTranscriptJson(mergeTopLevelJsonObjects(response.getTranscriptJson(), mappedResult.transcriptJson()));
            response.setAiSummaryText(mappedResult.aiSummaryText());
            SurveyResponse savedResponse = surveyResponseRepository.save(response);

            List<SurveyQuestion> questions = surveyQuestionRepository
                    .findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(callAttempt.getOperation().getSurvey().getId());
            Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId = new LinkedHashMap<>();
            questions.forEach(question -> optionsByQuestionId.put(
                    question.getId(),
                    surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(question.getId())
            ));

            int upsertedAnswers = 0;
            List<String> unmappedFields = new ArrayList<>(mappedResult.unmappedFields());
            for (IngestedSurveyAnswer answer : mappedResult.answers()) {
                Optional<SurveyQuestion> questionOptional = resolveQuestion(questions, answer);
                if (questionOptional.isEmpty()) {
                    String ref = answer.questionCode() != null ? answer.questionCode() : firstNonBlank(answer.questionTitle(), answer.rawValue());
                    if (ref != null) {
                        unmappedFields.add(ref);
                    }
                    log.warn(
                            "Provider answer mapping failed. provider={} callAttemptId={} providerCallId={} eventType={} ref={}",
                            callAttempt.getProvider(),
                            callAttempt.getId(),
                            callAttempt.getProviderCallId(),
                            event.eventType(),
                            ref
                    );
                    continue;
                }

                SurveyQuestion question = questionOptional.get();
                Optional<SurveyAnswer> existingAnswer = surveyAnswerRepository
                        .findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(savedResponse.getId(), question.getId());
                if (existingAnswer.isPresent() && shouldPreserveExistingAnswer(existingAnswer.get())) {
                    log.info(
                            "Skipping provider answer overwrite for live tool answer. callAttemptId={} providerCallId={} surveyResponseId={} questionCode={}",
                            callAttempt.getId(),
                            callAttempt.getProviderCallId(),
                            savedResponse.getId(),
                            question.getCode()
                    );
                    continue;
                }

                SurveyAnswer surveyAnswer = existingAnswer.orElseGet(() -> createSurveyAnswer(savedResponse, question));
                applyAnswer(surveyAnswer, question, optionsByQuestionId.getOrDefault(question.getId(), List.of()), answer);
                surveyAnswerRepository.save(surveyAnswer);
                upsertedAnswers++;
            }

            if (!unmappedFields.isEmpty()) {
                response.setTranscriptJson(mergeTranscriptJson(response.getTranscriptJson(), mappedResult.providerMetadataJson(), unmappedFields));
                log.warn(
                        "Provider result persisted with unmapped fields. provider={} callAttemptId={} providerCallId={} eventType={} count={} refs={}",
                        callAttempt.getProvider(),
                        callAttempt.getId(),
                        callAttempt.getProviderCallId(),
                        event.eventType(),
                        unmappedFields.size(),
                        unmappedFields
                );
            }

            int persistedAnswerCount = surveyAnswerRepository.findAllBySurveyResponse_IdAndDeletedAtIsNull(savedResponse.getId()).size();
            response.setCompletionPercent(calculateCompletionPercent(questions.size(), persistedAnswerCount, response.getCompletionPercent()));
            if (response.getStatus() == SurveyResponseStatus.COMPLETED && persistedAnswerCount > 0 && persistedAnswerCount < questions.size()) {
                response.setStatus(SurveyResponseStatus.PARTIAL);
            }
            SurveyResponse persistedResponse = surveyResponseRepository.save(response);
            providerExecutionObservationService.recordSurveyResult(
                    callAttempt,
                    event,
                    persistedResponse,
                    upsertedAnswers,
                    unmappedFields.size(),
                    buildResultObservationMessage(persistedResponse, upsertedAnswers, unmappedFields.size())
            );

            log.info(
                    "Survey response persisted. provider={} eventType={} callAttemptId={} providerCallId={} surveyResponseId={} status={} answersUpserted={} unmappedFieldCount={} transcriptAvailable={}",
                    callAttempt.getProvider(),
                    event.eventType(),
                    callAttempt.getId(),
                    callAttempt.getProviderCallId(),
                    persistedResponse.getId(),
                    persistedResponse.getStatus(),
                    upsertedAnswers,
                    unmappedFields.size(),
                    persistedResponse.getTranscriptText() != null && !persistedResponse.getTranscriptText().isBlank()
            );
        } catch (RuntimeException error) {
            providerExecutionObservationService.recordSurveyResultFailure(callAttempt, event, error.getMessage());
            log.warn(
                    "Survey response ingestion failed. provider={} eventType={} callAttemptId={} providerCallId={} message={}",
                    callAttempt.getProvider(),
                    event.eventType(),
                    callAttempt.getId(),
                    callAttempt.getProviderCallId(),
                    error.getMessage()
            );
            throw error;
        }
    }

    // The live interview, or an operator's edit, already completed or abandoned this response. Its
    // status, completion and answers follow the survey's skip logic and the consent decision, which the
    // provider's post-call data does not know about, so only the transcript and summary are added.
    private boolean isOutcomeDecided(SurveyResponse response) {
        return response.getStatus() == SurveyResponseStatus.COMPLETED || response.getStatus() == SurveyResponseStatus.ABANDONED;
    }

    private void attachTranscript(CallAttempt callAttempt, ProviderWebhookEvent event, SurveyResponse response, IngestedSurveyResult mappedResult) {
        if (mappedResult.transcriptText() != null && !mappedResult.transcriptText().isBlank()) {
            response.setTranscriptText(mappedResult.transcriptText());
        }
        if (mappedResult.aiSummaryText() != null && !mappedResult.aiSummaryText().isBlank()) {
            response.setAiSummaryText(mappedResult.aiSummaryText());
        }
        response.setTranscriptJson(mergeTopLevelJsonObjects(response.getTranscriptJson(), mappedResult.transcriptJson()));
        SurveyResponse persistedResponse = surveyResponseRepository.save(response);
        providerExecutionObservationService.recordSurveyResult(
                callAttempt,
                event,
                persistedResponse,
                0,
                0,
                "Transcript attached to the response decided during the call"
        );
        log.info(
                "Transcript attached to a decided survey response. provider={} eventType={} callAttemptId={} surveyResponseId={} status={}",
                callAttempt.getProvider(),
                event.eventType(),
                callAttempt.getId(),
                persistedResponse.getId(),
                persistedResponse.getStatus()
        );
    }

    private SurveyResponse createSurveyResponse(CallAttempt callAttempt) {
        SurveyResponse response = new SurveyResponse();
        response.setCompany(callAttempt.getCompany());
        response.setSurvey(callAttempt.getOperation().getSurvey());
        response.setOperation(callAttempt.getOperation());
        response.setOperationContact(callAttempt.getOperationContact());
        response.setCallAttempt(callAttempt);
        response.setRespondentPhone(OperationContactPhoneResolver.resolveDisplayPhoneNumber(callAttempt.getOperationContact()));
        response.setStartedAt(firstNonNull(callAttempt.getConnectedAt(), callAttempt.getDialedAt(), OffsetDateTime.now()));
        response.setCompletionPercent(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        response.setStatus(SurveyResponseStatus.PARTIAL);
        response.setTranscriptJson("{}");
        return response;
    }

    private SurveyAnswer createSurveyAnswer(SurveyResponse response, SurveyQuestion question) {
        SurveyAnswer answer = new SurveyAnswer();
        answer.setCompany(response.getCompany());
        answer.setSurveyResponse(response);
        answer.setSurveyQuestion(question);
        answer.setRetryCount(0);
        answer.setValid(true);
        answer.setAnswerJson("{}");
        return answer;
    }

    private void applyAnswer(
            SurveyAnswer surveyAnswer,
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            IngestedSurveyAnswer ingestedAnswer
    ) {
        surveyAnswer.setAnswerType(question.getQuestionType());
        surveyAnswer.setRawInputText(firstNonBlank(ingestedAnswer.rawText(), ingestedAnswer.rawValue()));
        surveyAnswer.setConfidenceScore(ingestedAnswer.confidenceScore());
        surveyAnswer.setValid(ingestedAnswer.valid());
        surveyAnswer.setInvalidReason(ingestedAnswer.invalidReason());
        surveyAnswer.setSelectedOption(null);
        surveyAnswer.setAnswerText(null);
        surveyAnswer.setAnswerNumber(null);

        switch (question.getQuestionType()) {
            case OPEN_ENDED -> {
                surveyAnswer.setAnswerText(firstNonBlank(ingestedAnswer.normalizedText(), ingestedAnswer.rawText(), ingestedAnswer.rawValue()));
            }
            case NUMBER -> {
                surveyAnswer.setAnswerNumber(ingestedAnswer.normalizedNumber());
                surveyAnswer.setAnswerText(ingestedAnswer.normalizedNumber() == null
                        ? firstNonBlank(ingestedAnswer.normalizedText(), ingestedAnswer.rawText(), ingestedAnswer.rawValue())
                        : ingestedAnswer.normalizedNumber().stripTrailingZeros().toPlainString());
                if (surveyAnswer.getAnswerNumber() == null) {
                    surveyAnswer.setValid(false);
                    surveyAnswer.setInvalidReason(firstNonBlank(ingestedAnswer.invalidReason(), "Number answer could not be normalized"));
                }
            }
            case RATING -> {
                surveyAnswer.setAnswerNumber(ingestedAnswer.normalizedNumber());
                if (surveyAnswer.getAnswerNumber() == null) {
                    surveyAnswer.setValid(false);
                    surveyAnswer.setInvalidReason(firstNonBlank(ingestedAnswer.invalidReason(), "Rating answer could not be normalized"));
                }
            }
            case SINGLE_CHOICE -> {
                SurveyQuestionOption matchedOption = matchSingleChoiceOption(options, ingestedAnswer);
                surveyAnswer.setSelectedOption(matchedOption);
                surveyAnswer.setAnswerText(matchedOption != null
                        ? matchedOption.getLabel()
                        : firstNonBlank(ingestedAnswer.normalizedText(), ingestedAnswer.rawText(), ingestedAnswer.rawValue()));
                if (matchedOption == null) {
                    surveyAnswer.setValid(false);
                    surveyAnswer.setInvalidReason(firstNonBlank(ingestedAnswer.invalidReason(), "Choice answer did not match an option"));
                }
            }
            case MULTI_CHOICE -> {
                List<String> normalizedValues = ingestedAnswer.normalizedValues() == null ? List.of() : ingestedAnswer.normalizedValues();
                List<String> matchedOptionIds = options.stream()
                        .filter(option -> normalizedValues.stream().anyMatch(value -> optionMatches(option, value)))
                        .map(option -> option.getId().toString())
                        .toList();
                surveyAnswer.setAnswerText(normalizedValues.isEmpty()
                        ? firstNonBlank(ingestedAnswer.normalizedText(), ingestedAnswer.rawText(), ingestedAnswer.rawValue())
                        : String.join(", ", normalizedValues));
                if (matchedOptionIds.isEmpty() && !normalizedValues.isEmpty()) {
                    surveyAnswer.setValid(false);
                    surveyAnswer.setInvalidReason(firstNonBlank(ingestedAnswer.invalidReason(), "Multi-choice answer did not match known options"));
                }
            }
        }

        surveyAnswer.setAnswerJson(buildAnswerJson(question.getQuestionType(), ingestedAnswer, surveyAnswer));
    }

    private boolean shouldPreserveExistingAnswer(SurveyAnswer existingAnswer) {
        return existingAnswer.isValid() && !hasProviderMetadata(existingAnswer.getAnswerJson());
    }

    private boolean hasProviderMetadata(String answerJson) {
        if (answerJson == null || answerJson.isBlank()) {
            return false;
        }
        try {
            return objectMapper.readTree(answerJson).has("providerMetadata");
        } catch (Exception ignored) {
            return false;
        }
    }

    private Optional<SurveyQuestion> resolveQuestion(List<SurveyQuestion> questions, IngestedSurveyAnswer answer) {
        if (answer.questionCode() != null) {
            Optional<SurveyQuestion> byCode = questions.stream()
                    .filter(question -> answer.questionCode().trim().equalsIgnoreCase(question.getCode()))
                    .findFirst();
            if (byCode.isPresent()) {
                return byCode;
            }
        }

        if (answer.questionOrder() != null) {
            Optional<SurveyQuestion> byOrder = questions.stream()
                    .filter(question -> answer.questionOrder().equals(question.getQuestionOrder()))
                    .findFirst();
            if (byOrder.isPresent()) {
                return byOrder;
            }
        }

        if (answer.questionTitle() != null) {
            String normalizedTitle = normalize(answer.questionTitle());
            return questions.stream()
                    .filter(question -> normalizedTitle.equals(normalize(question.getTitle())))
                    .findFirst();
        }

        return Optional.empty();
    }

    private SurveyQuestionOption matchSingleChoiceOption(List<SurveyQuestionOption> options, IngestedSurveyAnswer answer) {
        if (answer.normalizedValues() != null && !answer.normalizedValues().isEmpty()) {
            for (String value : answer.normalizedValues()) {
                Optional<SurveyQuestionOption> match = options.stream().filter(option -> optionMatches(option, value)).findFirst();
                if (match.isPresent()) {
                    return match.get();
                }
            }
        }

        String candidate = firstNonBlank(answer.normalizedText(), answer.rawText(), answer.rawValue());
        if (candidate == null) {
            return null;
        }
        return options.stream()
                .filter(option -> optionMatches(option, candidate))
                .findFirst()
                .orElse(null);
    }

    private boolean optionMatches(SurveyQuestionOption option, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalizedCandidate = normalize(candidate);
        return normalizedCandidate.equals(normalize(option.getOptionCode()))
                || normalizedCandidate.equals(normalize(option.getLabel()))
                || normalizedCandidate.equals(normalize(option.getValue()));
    }

    private String buildAnswerJson(QuestionType questionType, IngestedSurveyAnswer ingestedAnswer, SurveyAnswer answer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("questionType", questionType);
        payload.put("rawValue", ingestedAnswer.rawValue());
        payload.put("rawText", ingestedAnswer.rawText());
        payload.put("normalizedText", ingestedAnswer.normalizedText());
        payload.put("normalizedNumber", ingestedAnswer.normalizedNumber());
        payload.put("normalizedValues", ingestedAnswer.normalizedValues());
        payload.put("selectedOptionId", answer.getSelectedOption() != null ? answer.getSelectedOption().getId() : null);
        payload.put("providerMetadata", parseOrFallback(ingestedAnswer.providerMetadataJson()));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }

    // Keeps the existing top-level keys (such as the interview's consent state) readable where they are.
    private String mergeTranscriptJson(String transcriptJson, String providerMetadataJson, List<String> unmappedFields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        mergeJsonObjectInto(payload, transcriptJson);
        payload.put("providerMetadata", parseOrFallback(providerMetadataJson));
        payload.put("unmappedFields", unmappedFields);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            return firstNonBlank(transcriptJson, "{}");
        }
    }

    private String mergeTopLevelJsonObjects(String existingJson, String incomingJson) {
        Map<String, Object> payload = new LinkedHashMap<>();
        mergeJsonObjectInto(payload, existingJson);
        mergeJsonObjectInto(payload, incomingJson);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            return firstNonBlank(incomingJson, existingJson, "{}");
        }
    }

    private void mergeJsonObjectInto(Map<String, Object> payload, String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            if (!root.isObject()) {
                return;
            }
            Map<String, Object> values = objectMapper.convertValue(root, Map.class);
            payload.putAll(values);
        } catch (Exception ignored) {
        }
    }

    private String buildResultObservationMessage(SurveyResponse response, int answerCount, int unmappedFieldCount) {
        if (unmappedFieldCount > 0) {
            return "Survey result persisted with partial mapping";
        }
        if (response.getTranscriptText() == null || response.getTranscriptText().isBlank()) {
            return "Survey result persisted without transcript text";
        }
        return "Survey result persisted successfully";
    }

    private BigDecimal calculateCompletionPercent(int questionCount, int mappedAnswerCount, BigDecimal fallback) {
        if (questionCount <= 0) {
            return fallback;
        }
        return BigDecimal.valueOf((mappedAnswerCount * 100.0) / questionCount).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal defaultCompletion(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private Object parseOrFallback(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception ignored) {
            return rawJson;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim()
                .toLowerCase(Locale.ROOT)
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

        String decomposed = Normalizer.normalize(normalized, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
