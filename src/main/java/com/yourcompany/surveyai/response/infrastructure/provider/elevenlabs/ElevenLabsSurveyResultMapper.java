package com.yourcompany.surveyai.response.infrastructure.provider.elevenlabs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.provider.ProviderWebhookEvent;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.response.application.model.IngestedSurveyAnswer;
import com.yourcompany.surveyai.response.application.model.IngestedSurveyResult;
import com.yourcompany.surveyai.response.application.provider.ProviderSurveyResultMapper;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ElevenLabsSurveyResultMapper implements ProviderSurveyResultMapper {

    private static final Pattern QUESTION_RESPONSE_KEY_PATTERN = Pattern.compile("^survey_question_(\\d+)_response$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VOICE_DIRECTION_PATTERN = Pattern.compile("\\[(?:[a-zA-Z_\\-]{2,20})]|\\((?:[a-zA-Z_\\-]{2,20})\\)");
    private static final Set<String> NON_ANSWER_KEYS = Set.of(
            "survey_completion_status",
            "survey_consent_given",
            "user_feedback_summary"
    );

    private final ObjectMapper objectMapper;

    public ElevenLabsSurveyResultMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public CallProvider getProvider() {
        return CallProvider.ELEVENLABS;
    }

    @Override
    public IngestedSurveyResult map(CallAttempt callAttempt, ProviderWebhookEvent event) {
        JsonNode root = readTree(event.rawPayload());
        JsonNode data = root.has("data") && !root.get("data").isNull() ? root.get("data") : root;
        JsonNode analysis = data.path("analysis");

        List<IngestedSurveyAnswer> answers = extractStructuredAnswers(data, analysis);
        List<String> unmappedFields = extractUnmappedFields(data, analysis, answers);
        BigDecimal completionPercent = buildCompletionPercent(callAttempt, answers);

        return new IngestedSurveyResult(
                mapResponseStatus(event.jobStatus(), answers.isEmpty()),
                completionPercent,
                resolveStartedAt(data, callAttempt),
                resolveCompletedAt(event, data),
                firstNonBlank(sanitizeTranscriptText(event.transcriptText()), extractTranscriptText(data)),
                buildTranscriptJson(data, analysis, event, unmappedFields),
                firstNonBlank(text(analysis, "transcript_summary"), text(analysis, "summary")),
                safeJson(data),
                answers,
                unmappedFields
        );
    }

    private List<IngestedSurveyAnswer> extractStructuredAnswers(JsonNode data, JsonNode analysis) {
        JsonNode candidate = firstNonMissing(
                analysis.get("data_collection_results"),
                analysis.get("collected_data"),
                analysis.get("structured_data"),
                analysis.get("extracted_variables"),
                data.get("data_collection_results")
        );

        List<IngestedSurveyAnswer> answers = new ArrayList<>();
        if (candidate == null || candidate.isMissingNode() || candidate.isNull()) {
            return answers;
        }

        if (candidate.isObject()) {
            candidate.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (isNonAnswerField(key) || !hasAnswerValue(value)) {
                    return;
                }
                answers.add(buildAnswerFromEntry(key, value));
            });
            return answers;
        }

        if (candidate.isArray()) {
            for (JsonNode item : candidate) {
                String key = firstNonBlank(
                        text(item, "question_code"),
                        text(item, "questionCode"),
                        text(item, "question_title"),
                        text(item, "questionTitle"),
                        text(item, "field")
                );
                if (isNonAnswerField(key) || !hasAnswerValue(item)) {
                    continue;
                }
                answers.add(buildAnswerFromEntry(key, item));
            }
        }
        return answers;
    }

    private IngestedSurveyAnswer buildAnswerFromEntry(String key, JsonNode value) {
        String rawValue = value != null && value.isValueNode() ? value.asText() : text(value, "value", "answer", "result");
        String rawText = firstNonBlank(text(value, "text"), text(value, "raw"), rawValue);
        BigDecimal normalizedNumber = value != null && value.hasNonNull("number")
                ? value.get("number").decimalValue()
                : parseDecimal(rawValue);
        List<String> normalizedValues = extractNormalizedValues(value, rawValue);
        return new IngestedSurveyAnswer(
                key,
                resolveQuestionOrder(key, value),
                value != null ? firstNonBlank(text(value, "question_title"), text(value, "label")) : null,
                rawValue,
                rawText,
                normalizeScalar(rawValue),
                normalizedNumber,
                normalizedValues,
                value != null && value.hasNonNull("confidence") ? value.get("confidence").decimalValue() : null,
                !value.hasNonNull("valid") || value.get("valid").asBoolean(),
                value != null ? text(value, "invalid_reason", "reason") : null,
                safeJson(value)
        );
    }

    private List<String> extractNormalizedValues(JsonNode value, String rawValue) {
        List<String> values = new ArrayList<>();
        if (value != null && value.has("values") && value.get("values").isArray()) {
            for (JsonNode item : value.get("values")) {
                values.add(normalizeScalar(item.asText()));
            }
            return values;
        }
        if (value != null && value.has("selected_options") && value.get("selected_options").isArray()) {
            for (JsonNode item : value.get("selected_options")) {
                values.add(normalizeScalar(item.asText()));
            }
            return values;
        }
        if (rawValue != null && rawValue.contains(",")) {
            for (String item : rawValue.split(",")) {
                if (!item.isBlank()) {
                    values.add(normalizeScalar(item));
                }
            }
            return values;
        }
        if (rawValue != null && !rawValue.isBlank()) {
            values.add(normalizeScalar(rawValue));
        }
        return values;
    }

    private List<String> extractUnmappedFields(JsonNode data, JsonNode analysis, List<IngestedSurveyAnswer> answers) {
        JsonNode candidate = firstNonMissing(
                analysis.get("data_collection_results"),
                analysis.get("collected_data"),
                analysis.get("structured_data"),
                analysis.get("extracted_variables"),
                data.get("data_collection_results")
        );
        if (candidate == null || !candidate.isObject()) {
            return List.of();
        }

        List<String> mappedKeys = answers.stream()
                .map(IngestedSurveyAnswer::questionCode)
                .filter(key -> key != null && !key.isBlank())
                .toList();
        List<String> unmapped = new ArrayList<>();
        candidate.fieldNames().forEachRemaining(fieldName -> {
            if (isNonAnswerField(fieldName) || !hasAnswerValue(candidate.get(fieldName))) {
                return;
            }
            if (!mappedKeys.contains(fieldName)) {
                unmapped.add(fieldName);
            }
        });
        return unmapped;
    }

    // ElevenLabs lists every data-collection item, with a null value for questions that were not
    // answered (not asked, or the call ended first). Those are not answers.
    private boolean hasAnswerValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isValueNode()) {
            return !value.asText().isBlank();
        }
        for (String field : List.of("value", "answer", "result", "text", "raw", "number")) {
            JsonNode node = value.get(field);
            if (node != null && !node.isNull() && !(node.isTextual() && node.asText().isBlank())) {
                return true;
            }
        }
        for (String field : List.of("values", "selected_options")) {
            JsonNode node = value.get(field);
            if (node != null && node.isArray() && !node.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isNonAnswerField(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return NON_ANSWER_KEYS.contains(key.trim().toLowerCase(Locale.ROOT));
    }

    private Integer resolveQuestionOrder(String key, JsonNode value) {
        if (value != null) {
            if (value.hasNonNull("question_order")) {
                return value.get("question_order").asInt();
            }
            if (value.hasNonNull("questionOrder")) {
                return value.get("questionOrder").asInt();
            }
        }
        if (key == null) {
            return null;
        }
        Matcher matcher = QUESTION_RESPONSE_KEY_PATTERN.matcher(key.trim());
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private SurveyResponseStatus mapResponseStatus(CallJobStatus jobStatus, boolean noAnswers) {
        return switch (jobStatus) {
            case COMPLETED -> noAnswers ? SurveyResponseStatus.PARTIAL : SurveyResponseStatus.COMPLETED;
            case FAILED, DEAD_LETTER, CANCELLED -> noAnswers ? SurveyResponseStatus.ABANDONED : SurveyResponseStatus.PARTIAL;
            default -> SurveyResponseStatus.PARTIAL;
        };
    }

    private BigDecimal buildCompletionPercent(CallAttempt callAttempt, List<IngestedSurveyAnswer> answers) {
        int totalQuestions = answers.isEmpty() ? 0 : answers.size();
        if (totalQuestions <= 0) {
            return answers.isEmpty() ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : BigDecimal.valueOf(100).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf((answers.size() * 100.0) / totalQuestions).setScale(2, RoundingMode.HALF_UP);
    }

    private OffsetDateTime resolveStartedAt(JsonNode data, CallAttempt callAttempt) {
        String unixValue = text(data.path("metadata"), "start_time_unix_secs");
        if (unixValue != null && unixValue.matches("^\\d+$")) {
            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(unixValue)), ZoneOffset.UTC);
        }
        return callAttempt.getConnectedAt() != null ? callAttempt.getConnectedAt() : callAttempt.getDialedAt();
    }

    private OffsetDateTime resolveCompletedAt(ProviderWebhookEvent event, JsonNode data) {
        String unixValue = text(data.path("metadata"), "end_time_unix_secs");
        if (unixValue != null && unixValue.matches("^\\d+$")) {
            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(unixValue)), ZoneOffset.UTC);
        }
        return event.jobStatus() == CallJobStatus.COMPLETED ? event.occurredAt() : null;
    }

    private String buildTranscriptJson(JsonNode data, JsonNode analysis, ProviderWebhookEvent event, List<String> unmappedFields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transcript", firstNonMissingNode(data.get("transcript")));
        payload.put("analysis", firstNonMissingNode(analysis));
        payload.put("providerCallId", event.providerCallId());
        payload.put("unmappedFields", unmappedFields);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }

    private String extractTranscriptText(JsonNode data) {
        JsonNode transcript = data.get("transcript");
        if (transcript == null || transcript.isNull()) {
            return null;
        }
        if (transcript.isTextual()) {
            return sanitizePromptForSpeech(transcript.asText());
        }
        if (transcript.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : transcript) {
                String role = firstNonBlank(text(item, "role"), text(item, "speaker"));
                String message = sanitizePromptForSpeech(firstNonBlank(text(item, "message"), text(item, "text")));
                if (message == null) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                if (role != null) {
                    builder.append(role).append(": ");
                }
                builder.append(message);
            }
            return builder.toString();
        }
        return safeJson(transcript);
    }

    private String sanitizePromptForSpeech(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        String withoutDirections = VOICE_DIRECTION_PATTERN.matcher(trimmed).replaceAll(" ");
        String normalizedWhitespace = withoutDirections.replaceAll("\\s+", " ").trim();
        return normalizedWhitespace.isBlank() ? null : normalizedWhitespace;
    }

    private String sanitizeTranscriptText(String value) {
        if (value == null) {
            return null;
        }
        String[] lines = value.split("\\R");
        List<String> sanitizedLines = new ArrayList<>();
        for (String line : lines) {
            String sanitized = sanitizePromptForSpeech(line);
            if (sanitized != null) {
                sanitizedLines.add(sanitized);
            }
        }
        return sanitizedLines.isEmpty() ? null : String.join("\n", sanitizedLines);
    }

    private JsonNode readTree(String rawJson) {
        try {
            return objectMapper.readTree(rawJson == null ? "{}" : rawJson);
        } catch (JsonProcessingException error) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String... fields) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        for (String field : fields) {
            if (node.hasNonNull(field)) {
                return node.get(field).asText();
            }
        }
        return null;
    }

    private JsonNode firstNonMissing(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private Object firstNonMissingNode(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? Map.of() : node;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeScalar(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.equals("yes") || normalized.equals("true")) {
            return "YES";
        }
        if (normalized.equals("no") || normalized.equals("false")) {
            return "NO";
        }
        return trimmed;
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }
}
