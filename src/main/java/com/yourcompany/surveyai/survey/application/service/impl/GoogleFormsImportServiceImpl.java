package com.yourcompany.surveyai.survey.application.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourcompany.surveyai.auth.application.RequestAuthContext;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.survey.application.dto.request.CreateSurveyQuestionOptionRequest;
import com.yourcompany.surveyai.survey.application.dto.request.CreateSurveyQuestionRequest;
import com.yourcompany.surveyai.survey.application.dto.request.CreateSurveyRequest;
import com.yourcompany.surveyai.survey.application.dto.request.ImportGoogleFormRequest;
import com.yourcompany.surveyai.survey.application.dto.response.ImportGoogleFormResponseDto;
import com.yourcompany.surveyai.survey.application.dto.response.SurveyQuestionResponseDto;
import com.yourcompany.surveyai.survey.application.dto.response.SurveyResponseDto;
import com.yourcompany.surveyai.survey.application.service.GoogleFormsImportService;
import com.yourcompany.surveyai.survey.application.service.SurveyQuestionOptionService;
import com.yourcompany.surveyai.survey.application.service.SurveyQuestionService;
import com.yourcompany.surveyai.survey.application.service.SurveyService;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.infrastructure.googleforms.GoogleFormsClient;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class GoogleFormsImportServiceImpl implements GoogleFormsImportService {

    private static final Pattern FORM_ID_PATTERN = Pattern.compile("/forms/d(?:/e)?/([a-zA-Z0-9_-]+)");
    private static final Pattern QUESTION_CODE_PATTERN = Pattern.compile("^\\s*([A-Za-z]\\d+(?:\\.\\d+)?)\\s*[\\).:-]?\\s*(.*)$");
    private static final Pattern QUESTION_REFERENCE_PATTERN = Pattern.compile("\\b([A-Za-z]\\d+(?:\\.\\d+)?)\\b");
    private static final Pattern RATING_1_TO_5_PATTERN = Pattern.compile(
            "(?:\\b1\\s*(?:-|/|to|ile|den|dan)?\\s*5\\b|5\\s*(?:uzerinden|u?zerinden)|p(?:uan|aun)la|p(?:uan|aun)layiniz|p(?:uan|aun)layin|degerlendir)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern RATING_1_TO_10_PATTERN = Pattern.compile(
            "(?:\\b1\\s*(?:-|/|to|ile|den|dan)?\\s*10\\b|10\\s*(?:uzerinden|u?zerinden)|10\\s*p(?:uan|aun))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private final GoogleFormsClient googleFormsClient;
    private final SurveyService surveyService;
    private final SurveyQuestionService surveyQuestionService;
    private final SurveyQuestionOptionService surveyQuestionOptionService;
    private final RequestAuthContext requestAuthContext;
    private final ObjectMapper objectMapper;

    public GoogleFormsImportServiceImpl(
            GoogleFormsClient googleFormsClient,
            SurveyService surveyService,
            SurveyQuestionService surveyQuestionService,
            SurveyQuestionOptionService surveyQuestionOptionService,
            RequestAuthContext requestAuthContext,
            ObjectMapper objectMapper
    ) {
        this.googleFormsClient = googleFormsClient;
        this.surveyService = surveyService;
        this.surveyQuestionService = surveyQuestionService;
        this.surveyQuestionOptionService = surveyQuestionOptionService;
        this.requestAuthContext = requestAuthContext;
        this.objectMapper = objectMapper;
    }

    @Override
    public ImportGoogleFormResponseDto importForm(UUID companyId, ImportGoogleFormRequest request) {
        String formId = extractFormId(request.getFormUrl());
        JsonNode form = googleFormsClient.fetchForm(request.getAccessToken(), formId);

        JsonNode info = form.path("info");
        String title = requireText(info.path("title").asText(null), "Google Form başlığı bulunamadı.");
        String description = trimToNull(info.path("description").asText(null));
        List<ImportedQuestion> importedQuestions = mapQuestions(form.path("items"));

        if (importedQuestions.isEmpty()) {
            throw new ValidationException("Google Form içinde içe aktarılabilir soru bulunamadı.");
        }

        CreateSurveyRequest createSurveyRequest = new CreateSurveyRequest();
        createSurveyRequest.setName(title);
        createSurveyRequest.setDescription(description);
        createSurveyRequest.setLanguageCode(resolveLanguageCode(request.getLanguageCode()));
        createSurveyRequest.setIntroPrompt(trimToNull(request.getIntroPrompt()));
        createSurveyRequest.setClosingPrompt(trimToNull(request.getClosingPrompt()));
        createSurveyRequest.setMaxRetryPerQuestion(request.getMaxRetryPerQuestion() != null ? request.getMaxRetryPerQuestion() : 2);
        createSurveyRequest.setCreatedByUserId(requestAuthContext.requireUser().getId());
        createSurveyRequest.setSourceProvider("GOOGLE_FORMS");
        createSurveyRequest.setSourceExternalId(formId);
        createSurveyRequest.setSourcePayloadJson(buildSurveySourcePayload(formId, info));

        SurveyResponseDto createdSurvey = surveyService.createSurvey(companyId, createSurveyRequest);

        for (int index = 0; index < importedQuestions.size(); index += 1) {
            ImportedQuestion importedQuestion = importedQuestions.get(index);
            CreateSurveyQuestionRequest questionRequest = new CreateSurveyQuestionRequest();
            String preferredCode = trimToNull(importedQuestion.questionCodeHint());
            questionRequest.setCode(
                    preferredCode != null
                            ? normalizeCode(preferredCode, "question_" + (index + 1))
                            : normalizeCode("question_" + (index + 1) + "_" + slugify(importedQuestion.title()), "question_" + (index + 1))
            );
            questionRequest.setQuestionOrder(index + 1);
            questionRequest.setQuestionType(importedQuestion.questionType());
            questionRequest.setTitle(importedQuestion.title());
            questionRequest.setDescription(importedQuestion.description());
            questionRequest.setRequired(importedQuestion.required());
            questionRequest.setRetryPrompt(null);
            questionRequest.setBranchConditionJson(importedQuestion.branchConditionJson());
            questionRequest.setSettingsJson(writeSettingsJson(importedQuestion));
            questionRequest.setSourceExternalId(importedQuestion.sourceExternalId());
            questionRequest.setSourcePayloadJson(importedQuestion.sourcePayloadJson());

            SurveyQuestionResponseDto savedQuestion = surveyQuestionService.addQuestion(companyId, createdSurvey.id(), questionRequest);

            for (int optionIndex = 0; optionIndex < importedQuestion.options().size(); optionIndex += 1) {
                String optionLabel = importedQuestion.options().get(optionIndex);
                CreateSurveyQuestionOptionRequest optionRequest = new CreateSurveyQuestionOptionRequest();
                optionRequest.setOptionOrder(optionIndex + 1);
                optionRequest.setOptionCode(
                        normalizeCode("option_" + (optionIndex + 1) + "_" + slugify(optionLabel), "option_" + (optionIndex + 1))
                );
                optionRequest.setLabel(optionLabel);
                optionRequest.setValue(slugify(optionLabel));
                optionRequest.setActive(true);

                surveyQuestionOptionService.addOption(companyId, createdSurvey.id(), savedQuestion.id(), optionRequest);
            }
        }

        return new ImportGoogleFormResponseDto(createdSurvey, importedQuestions.size());
    }

    private List<ImportedQuestion> mapQuestions(JsonNode itemsNode) {
        if (!itemsNode.isArray()) {
            return List.of();
        }

        List<ImportedQuestion> questions = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            if (item.has("questionGroupItem")) {
                questions.addAll(mapQuestionGroupItem(item));
                continue;
            }
            if (!item.has("questionItem")) {
                throw new ValidationException("Form içinde desteklenmeyen bir öğe bulundu. Yalnızca soru öğeleri içe aktarılabilir.");
            }

            JsonNode question = item.path("questionItem").path("question");
            String title = requireText(item.path("title").asText(null), "Bir sorunun başlığı eksik.");
            String description = trimToNull(item.path("description").asText(null));
            boolean required = question.path("required").asBoolean(false);
            String sourceExternalId = trimToNull(question.path("questionId").asText(null));
            String sourcePayloadJson = writeSourcePayloadJson(item);

            if (question.has("textQuestion")) {
                boolean paragraph = question.path("textQuestion").path("paragraph").asBoolean(false);
                questions.add(createImportedQuestion(
                        title,
                        description,
                        required,
                        QuestionType.OPEN_ENDED,
                        paragraph ? "long_text" : "short_text",
                        null,
                        List.of(),
                        sourceExternalId,
                        sourcePayloadJson,
                        null,
                        null,
                        null,
                        null
                ));
                continue;
            }

            if (question.has("choiceQuestion")) {
                questions.add(mapChoiceQuestion(title, description, required, question.path("choiceQuestion"), sourceExternalId, sourcePayloadJson));
                continue;
            }

            if (question.has("scaleQuestion")) {
                questions.add(mapScaleQuestion(title, description, required, question.path("scaleQuestion"), sourceExternalId, sourcePayloadJson));
                continue;
            }

            if (question.has("dateQuestion")) {
                questions.add(createImportedQuestion(
                        title,
                        description,
                        required,
                        QuestionType.OPEN_ENDED,
                        "date",
                        null,
                        List.of(),
                        sourceExternalId,
                        sourcePayloadJson,
                        null,
                        null,
                        null,
                        null
                ));
                continue;
            }

            ImportedQuestion inferredRatingQuestion = inferRatingQuestion(
                    title,
                    description,
                    required,
                    sourceExternalId,
                    sourcePayloadJson
            );
            if (inferredRatingQuestion != null) {
                questions.add(inferredRatingQuestion);
                continue;
            }

            throw new ValidationException("Desteklenmeyen Google Forms soru tipi bulundu: " + title);
        }

        return questions;
    }

    private ImportedQuestion mapChoiceQuestion(
            String title,
            String description,
            boolean required,
            JsonNode choiceQuestion,
            String sourceExternalId,
            String sourcePayloadJson
    ) {
        String choiceType = choiceQuestion.path("type").asText("");
        List<String> options = new ArrayList<>();
        Iterator<JsonNode> iterator = choiceQuestion.path("options").elements();
        while (iterator.hasNext()) {
            JsonNode option = iterator.next();
            options.add(requireText(option.path("value").asText(null), "Bir seçeneğin metni eksik."));
        }

        if (options.isEmpty()) {
            throw new ValidationException("Seçimli sorularda en az bir seçenek bulunmalıdır: " + title);
        }

        return switch (choiceType) {
            case "RADIO" -> createImportedQuestion(title, description, required, QuestionType.SINGLE_CHOICE, "single_choice", null, options, sourceExternalId, sourcePayloadJson, null, null, null, null);
            case "CHECKBOX" -> createImportedQuestion(title, description, required, QuestionType.MULTI_CHOICE, "multi_choice", null, options, sourceExternalId, sourcePayloadJson, null, null, null, null);
            case "DROP_DOWN", "SELECT" -> createImportedQuestion(title, description, required, QuestionType.SINGLE_CHOICE, "dropdown", null, options, sourceExternalId, sourcePayloadJson, null, null, null, null);
            default -> throw new ValidationException("Desteklenmeyen choiceQuestion tipi bulundu: " + choiceType);
        };
    }

    private ImportedQuestion mapScaleQuestion(
            String title,
            String description,
            boolean required,
            JsonNode scaleQuestion,
            String sourceExternalId,
            String sourcePayloadJson
    ) {
        int low = scaleQuestion.path("low").asInt(1);
        int high = scaleQuestion.path("high").asInt(5);
        if (low != 1 || (high != 5 && high != 10)) {
            throw new ValidationException("Yalnızca 1-5 veya 1-10 ölçekli sorular içe aktarılabilir: " + title);
        }

        return createImportedQuestion(
                title,
                description,
                required,
                QuestionType.RATING,
                high == 5 ? "rating_1_5" : "rating_1_10",
                high,
                List.of(),
                sourceExternalId,
                sourcePayloadJson,
                null,
                null,
                null,
                null
        );
    }

    private ImportedQuestion inferRatingQuestion(
            String title,
            String description,
            boolean required,
            String sourceExternalId,
            String sourcePayloadJson
    ) {
        String combined = normalizeTurkishText((title == null ? "" : title) + " " + (description == null ? "" : description));

        if (RATING_1_TO_10_PATTERN.matcher(combined).find()) {
            return createImportedQuestion(
                    title,
                    description,
                    required,
                    QuestionType.RATING,
                    "rating_1_10",
                    10,
                    List.of(),
                    sourceExternalId,
                    sourcePayloadJson,
                    null,
                    null,
                    null,
                    null
            );
        }

        if (RATING_1_TO_5_PATTERN.matcher(combined).find()) {
            return createImportedQuestion(
                    title,
                    description,
                    required,
                    QuestionType.RATING,
                    "rating_1_5",
                    5,
                    List.of(),
                    sourceExternalId,
                    sourcePayloadJson,
                    null,
                    null,
                    null,
                    null
            );
        }

        return null;
    }

    private ImportedQuestion createImportedQuestion(
            String title,
            String description,
            boolean required,
            QuestionType questionType,
            String builderType,
            Integer ratingScale,
            List<String> options,
            String sourceExternalId,
            String sourcePayloadJson,
            String groupCode,
            String groupTitle,
            String rowLabel,
            String optionSetCode
    ) {
        String questionCodeHint = groupCode != null
                ? normalizeCode(groupCode + "_" + slugify(rowLabel != null ? rowLabel : title), "question")
                : extractLeadingQuestionCode(title);
        String branchConditionJson = inferBranchConditionJson(title, description, groupCode, groupCode != null ? groupCode : questionCodeHint);
        return new ImportedQuestion(
                title,
                description,
                required,
                questionType,
                builderType,
                ratingScale,
                options,
                sourceExternalId,
                sourcePayloadJson,
                groupCode,
                groupTitle,
                rowLabel,
                optionSetCode,
                questionCodeHint,
                branchConditionJson
        );
    }

    private String writeSettingsJson(ImportedQuestion importedQuestion) {
        try {
            ObjectNode node = objectMapper.createObjectNode().put("builderType", importedQuestion.builderType());
            if (importedQuestion.ratingScale() != null) {
                node.put("ratingScale", importedQuestion.ratingScale());
            }
            if (importedQuestion.groupCode() != null) {
                node.put("groupCode", importedQuestion.groupCode());
                node.put("groupTitle", importedQuestion.groupTitle() != null ? importedQuestion.groupTitle() : importedQuestion.title());
                node.put("rowLabel", importedQuestion.rowLabel() != null ? importedQuestion.rowLabel() : importedQuestion.title());
                String rowCode = slugify(importedQuestion.rowLabel() != null ? importedQuestion.rowLabel() : importedQuestion.title());
                node.put("rowCode", rowCode);
                node.put("rowKey", rowCode);
                if (importedQuestion.optionSetCode() != null) {
                    node.put("optionSetCode", importedQuestion.optionSetCode());
                }
                node.put("matrixType", "GRID_SINGLE_CHOICE");
            }
            appendChoiceAliases(node, importedQuestion.options());
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new ValidationException("Import ayarları hazırlanamadı.");
        }
    }

    private String buildSurveySourcePayload(String formId, JsonNode info) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("formId", formId);
            payload.put("title", trimToNull(info.path("title").asText(null)));
            payload.put("description", trimToNull(info.path("description").asText(null)));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new ValidationException("Google Forms kaynak bilgisi hazırlanamadı.");
        }
    }

    private String writeSourcePayloadJson(JsonNode item) {
        try {
            return objectMapper.writeValueAsString(item);
        } catch (Exception exception) {
            throw new ValidationException("Google Forms soru metadatası hazırlanamadı.");
        }
    }

    private List<ImportedQuestion> mapQuestionGroupItem(JsonNode item) {
        String title = requireText(item.path("title").asText(null), "Bir soru grubunun başlığı eksik.");
        String description = trimToNull(item.path("description").asText(null));
        JsonNode group = item.path("questionGroupItem");
        List<String> options = readQuestionGroupOptions(group.path("grid"), title);
        String groupCode = resolveQuestionGroupCode(title);
        String optionSetCode = inferOptionSetCode(options);
        List<ImportedQuestion> questions = new ArrayList<>();

        for (JsonNode groupQuestion : group.path("questions")) {
            String rowLabel = requireText(
                    groupQuestion.path("rowQuestion").path("title").asText(null),
                    "Soru grubunda satır başlığı eksik."
            );
            boolean required = groupQuestion.path("required").asBoolean(false);
            String sourceExternalId = trimToNull(groupQuestion.path("questionId").asText(null));
            questions.add(createImportedQuestion(
                    rowLabel,
                    description,
                    required,
                    QuestionType.SINGLE_CHOICE,
                    "single_choice",
                    null,
                    options,
                    sourceExternalId,
                    writeGroupedSourcePayloadJson(item, groupQuestion, groupCode, title, rowLabel, optionSetCode, options),
                    groupCode,
                    title,
                    rowLabel,
                    optionSetCode
            ));
        }

        if (questions.isEmpty()) {
            throw new ValidationException("Google Forms soru grubunda içe aktarılabilir satır bulunamadı: " + title);
        }
        return questions;
    }

    private List<String> readQuestionGroupOptions(JsonNode grid, String title) {
        List<String> options = new ArrayList<>();
        Iterator<JsonNode> iterator = grid.path("columns").path("options").elements();
        while (iterator.hasNext()) {
            JsonNode option = iterator.next();
            options.add(requireText(option.path("value").asText(null), "Soru grubunda seçenek metni eksik."));
        }
        if (options.isEmpty()) {
            throw new ValidationException("Soru grubunda en az bir sütun seçeneği bulunmalıdır: " + title);
        }
        return List.copyOf(options);
    }

    private String writeGroupedSourcePayloadJson(
            JsonNode item,
            JsonNode groupQuestion,
            String groupCode,
            String groupTitle,
            String rowLabel,
            String optionSetCode,
            List<String> options
    ) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("item", item);
            payload.set("groupQuestion", groupQuestion);
            payload.put("groupCode", groupCode);
            payload.put("groupTitle", groupTitle);
            payload.put("rowLabel", rowLabel);
            if (optionSetCode != null) {
                payload.put("optionSetCode", optionSetCode);
            }
            ArrayNode optionArray = payload.putArray("options");
            options.forEach(optionArray::add);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new ValidationException("Google Forms grup soru metadatası hazırlanamadı.");
        }
    }

    private void appendChoiceAliases(ObjectNode settingsNode, List<String> options) {
        Map<String, List<String>> aliases = buildKnownChoiceAliases(options);
        if (aliases.isEmpty()) {
            return;
        }
        ObjectNode aliasesNode = settingsNode.putObject("aliases");
        aliases.forEach((key, values) -> {
            ArrayNode valueArray = aliasesNode.putArray(key);
            values.forEach(valueArray::add);
        });
    }

    private Map<String, List<String>> buildKnownChoiceAliases(List<String> options) {
        List<String> normalizedOptions = options.stream()
                .map(this::normalizeTurkishText)
                .toList();
        if (normalizedOptions.containsAll(List.of(
                "cok iyi taniyorum",
                "taniyorum",
                "biraz taniyorum",
                "duydum ama tanimiyorum",
                "hic duymadim"
        ))) {
            Map<String, List<String>> aliases = new LinkedHashMap<>();
            aliases.put("cok_iyi_taniyorum", List.of("cok iyi taniyorum", "çok iyi tanıyorum", "cok iyi biliyorum"));
            aliases.put("taniyorum", List.of("taniyorum", "tanıyorum", "biliyorum"));
            aliases.put("biraz_taniyorum", List.of("biraz taniyorum", "biraz tanıyorum", "az taniyorum"));
            aliases.put("duydum_ama_tanimiyorum", List.of("duydum ama tanimiyorum", "duydum ama tanımıyorum", "ismini duydum"));
            aliases.put("hic_duymadim", List.of("hic duymadim", "hiç duymadım", "duymadim", "duymadım"));
            return aliases;
        }
        return Map.of();
    }

    private String inferOptionSetCode(List<String> options) {
        return buildKnownChoiceAliases(options).isEmpty() ? null : "familiarity_5";
    }

    private String resolveQuestionGroupCode(String title) {
        String extractedCode = extractLeadingQuestionCode(title);
        return extractedCode != null ? extractedCode : normalizeCode("group_" + slugify(title), "group");
    }

    private String extractLeadingQuestionCode(String text) {
        String value = trimToNull(text);
        if (value == null) {
            return null;
        }
        Matcher matcher = QUESTION_CODE_PATTERN.matcher(value);
        return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private String inferBranchConditionJson(
            String title,
            String description,
            String groupCode,
            String currentQuestionCodeHint
    ) {
        String combined = trimToNull((title == null ? "" : title) + " " + (description == null ? "" : description));
        if (combined == null) {
            return "{}";
        }

        String normalizedCombined = normalizeTurkishText(combined);
        if (!normalizedCombined.contains("sorulmayacak") && !normalizedCombined.contains("sorulmaz")) {
            return "{}";
        }

        String referencedCode = extractReferencedQuestionCode(combined, currentQuestionCodeHint);
        List<String> selectedOptionCodes = inferSelectedOptionCodes(normalizedCombined);
        if (referencedCode == null || selectedOptionCodes.isEmpty()) {
            return "{}";
        }

        ObjectNode root = objectMapper.createObjectNode();
        List<String> inferredAnswerTags = inferBranchAnswerTags(selectedOptionCodes);
        boolean useKnowledgePositiveAskIf = inferredAnswerTags.contains("knowledge_negative");
        ObjectNode branchNode = root.putObject(useKnowledgePositiveAskIf ? "askIf" : "skipIf");
        if (groupCode != null) {
            branchNode.put("groupCode", referencedCode);
            if (currentQuestionCodeHint == null || !normalizeTurkishText(referencedCode).equals(normalizeTurkishText(currentQuestionCodeHint))) {
                branchNode.put("sameRowCode", true);
            }
        } else {
            branchNode.put("questionCode", referencedCode);
        }
        if (useKnowledgePositiveAskIf) {
            branchNode.putArray("answerTagsAnyOf").add("knowledge_positive");
        } else {
            ArrayNode optionArray = branchNode.putArray("selectedOptionCodes");
            selectedOptionCodes.forEach(optionArray::add);
            if (!inferredAnswerTags.isEmpty()) {
                ArrayNode tagArray = branchNode.putArray("answerTagsAnyOf");
                inferredAnswerTags.forEach(tagArray::add);
            }
        }
        return root.toString();
    }

    private String extractReferencedQuestionCode(String text, String currentQuestionCodeHint) {
        Matcher matcher = QUESTION_REFERENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).toUpperCase(Locale.ROOT);
            if (currentQuestionCodeHint == null || !candidate.equalsIgnoreCase(currentQuestionCodeHint)) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> inferSelectedOptionCodes(String normalizedText) {
        List<String> selectedOptionCodes = new ArrayList<>();
        if (normalizedText.contains("hic duymadim")) {
            selectedOptionCodes.add("hic_duymadim");
        }
        if (normalizedText.contains("duydum ama tanimiyorum") || normalizedText.contains("tanimiyorum")) {
            selectedOptionCodes.add("duydum_ama_tanimiyorum");
        }
        if (normalizedText.contains("bilmiyorum")) {
            selectedOptionCodes.add("bilmiyorum");
        }
        if (normalizedText.contains("hayir")) {
            selectedOptionCodes.add("hayir");
        }
        if (normalizedText.contains("evet")) {
            selectedOptionCodes.add("evet");
        }
        return selectedOptionCodes.stream().distinct().toList();
    }

    private List<String> inferBranchAnswerTags(List<String> selectedOptionCodes) {
        List<String> tags = new ArrayList<>();
        for (String optionCode : selectedOptionCodes) {
            String normalized = normalizeTurkishText(optionCode);
            if (normalized.contains("hic duymad")) {
                addBranchAnswerTag(tags, "knowledge_negative");
                addBranchAnswerTag(tags, "knowledge_never_heard");
            }
            if (normalized.contains("duydum") && normalized.contains("tanimiyorum")) {
                addBranchAnswerTag(tags, "knowledge_negative");
                addBranchAnswerTag(tags, "knowledge_heard_but_unknown");
            }
            if ((normalized.contains("tanimiyorum") || normalized.contains("bilmiyorum"))
                    && !normalized.contains("taniyorum")) {
                addBranchAnswerTag(tags, "knowledge_negative");
            }
            if ((normalized.contains("taniyorum") || normalized.contains("biliyorum"))
                    && !normalized.contains("tanimiyorum")) {
                addBranchAnswerTag(tags, "knowledge_positive");
            }
            if (normalized.contains("evet")) {
                addBranchAnswerTag(tags, "yes");
            }
            if (normalized.contains("hayir")) {
                addBranchAnswerTag(tags, "no");
            }
        }
        return List.copyOf(tags);
    }

    private void addBranchAnswerTag(List<String> tags, String value) {
        if (!tags.contains(value)) {
            tags.add(value);
        }
    }

    private String extractFormId(String formUrl) {
        String normalizedUrl = requireText(formUrl, "Form URL zorunludur.");
        Matcher matcher = FORM_ID_PATTERN.matcher(normalizedUrl);
        if (!matcher.find()) {
            throw new ValidationException("Google Forms URL'sinden form kimliği çıkarılamadı.");
        }
        return matcher.group(1);
    }

    private String resolveLanguageCode(String languageCode) {
        String normalized = trimToNull(languageCode);
        return normalized != null ? normalized.toLowerCase(Locale.ROOT) : "tr";
    }

    private String requireText(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ValidationException(message);
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String slugify(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private String normalizeCode(String value, String fallback) {
        String normalized = value
                .trim()
                .replaceAll("[^a-zA-Z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) {
            return fallback;
        }
        return normalized.substring(0, Math.min(normalized.length(), 100));
    }

    private String normalizeTurkishText(String value) {
        return value == null ? "" : value
                .toLowerCase(Locale.ROOT)
                .replace('\u0131', 'i')
                .replace('\u0130', 'i')
                .replace('\u011f', 'g')
                .replace('\u011e', 'g')
                .replace('\u00fc', 'u')
                .replace('\u00dc', 'u')
                .replace('\u015f', 's')
                .replace('\u015e', 's')
                .replace('\u00f6', 'o')
                .replace('\u00d6', 'o')
                .replace('\u00e7', 'c')
                .replace('\u00c7', 'c');
    }

    private record ImportedQuestion(
            String title,
            String description,
            boolean required,
            QuestionType questionType,
            String builderType,
            Integer ratingScale,
            List<String> options,
            String sourceExternalId,
            String sourcePayloadJson,
            String groupCode,
            String groupTitle,
            String rowLabel,
            String optionSetCode,
            String questionCodeHint,
            String branchConditionJson
    ) {
    }
}
