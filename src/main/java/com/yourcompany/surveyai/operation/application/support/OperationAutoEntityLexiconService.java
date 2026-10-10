package com.yourcompany.surveyai.operation.application.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourcompany.surveyai.survey.application.support.SurveyQuestionAutoLexiconService;
import com.yourcompany.surveyai.survey.application.support.TurkeyGeoDataService;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class OperationAutoEntityLexiconService {

    private static final String PREVIEW_FIELD = "autoEntityLexiconPreview";
    private static final Pattern CURRENT_MAYOR_PATTERN = Pattern.compile("\\|\\s*gorevdeki\\s*=\\s*\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?]]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TERM_BLOCK_PATTERN = Pattern.compile("'''\\[\\[TBMM\\s+(\\d+)\\.\\s+donem milletvekilleri listesi\\|TBMM\\s+\\d+\\.\\s+Donem]]'''(.*?)! colspan", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?]]");
    private static final List<OccupationSeed> OCCUPATION_SEEDS = List.of(
            new OccupationSeed("Ev hanimi", List.of("Ev hanimi", "Ev kadini", "Ev yoneticisi")),
            new OccupationSeed("Emekli", List.of("Emekli", "Emekliyim")),
            new OccupationSeed("Ogrenci", List.of("Ogrenci", "Ogrenciyim")),
            new OccupationSeed("Issiz", List.of("Issiz", "Issizim", "Is ariyorum")),
            new OccupationSeed("Memur", List.of("Memur", "Devlet memuru", "Kamu calisani")),
            new OccupationSeed("Ogretmen", List.of("Ogretmen", "Ogretim gorevlisi", "Akademisyen")),
            new OccupationSeed("Doktor", List.of("Doktor", "Hekim", "Uzman doktor")),
            new OccupationSeed("Hemsire", List.of("Hemsire", "Saglik calisani")),
            new OccupationSeed("Eczaci", List.of("Eczaci")),
            new OccupationSeed("Muhendis", List.of("Muhendis", "Bilgisayar muhendisi", "Insaat muhendisi")),
            new OccupationSeed("Mimar", List.of("Mimar")),
            new OccupationSeed("Yazilimci", List.of("Yazilimci", "Programci", "Yazilim gelistirici")),
            new OccupationSeed("Avukat", List.of("Avukat")),
            new OccupationSeed("Muhasebeci", List.of("Muhasebeci", "Mali musavir")),
            new OccupationSeed("Esnaf", List.of("Esnaf", "Dukkan sahibi")),
            new OccupationSeed("Isci", List.of("Isci", "Fabrika iscisi")),
            new OccupationSeed("Girisimci", List.of("Girisimci", "Is insani", "Isletmeci")),
            new OccupationSeed("Yonetici", List.of("Yonetici", "Mudur", "Genel mudur")),
            new OccupationSeed("Ciftci", List.of("Ciftci", "Tarimci")),
            new OccupationSeed("Gazeteci", List.of("Gazeteci", "Muhabir")),
            new OccupationSeed("Sofor", List.of("Sofor", "Surucu", "Taksici")),
            new OccupationSeed("Berber", List.of("Berber", "Kuafor")),
            new OccupationSeed("Asci", List.of("Asci", "Pastaci", "Garson")),
            new OccupationSeed("Guvenlik gorevlisi", List.of("Guvenlik gorevlisi")),
            new OccupationSeed("Serbest meslek", List.of("Serbest meslek", "Serbest calisiyor", "Kendi hesabima"))
    );
    private static final List<String> OCCUPATION_HINTS = List.of(
            "meslek", "mesleginiz", "meslegi", "meslegini", "ne is yapiyorsunuz",
            "is durumu", "calisma durumu", "occupation", "job"
    );
    private static final List<String> POLITICAL_HINTS = List.of(
            "siyaset", "siyasetci", "aday", "milletvekili", "belediye", "secim", "oy", "parti"
    );
    private static final List<String> PERSON_OPEN_ENDED_HINTS = List.of(
            "kim", "kimi", "kime", "hangi siyasetci", "en begendiginiz siyasetci",
            "akliniza ilk gelen", "ilk gelen", "begenilen siyasetci"
    );
    private static final List<String> NON_PERSON_POLITICAL_LABEL_HINTS = List.of(
            "parti", "partiye", "aday", "adaylara", "gore", "genel", "secim", "secimlerde",
            "her ikisi", "ikisi", "cevap", "fikir", "yok", "okur", "yazar", "diger"
    );

    private final ObjectMapper objectMapper;
    private final SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService;
    private final TurkeyGeoDataService turkeyGeoDataService;
    private final HttpClient httpClient;

    public OperationAutoEntityLexiconService(
            ObjectMapper objectMapper,
            SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService,
            TurkeyGeoDataService turkeyGeoDataService
    ) {
        this.objectMapper = objectMapper;
        this.surveyQuestionAutoLexiconService = surveyQuestionAutoLexiconService;
        this.turkeyGeoDataService = turkeyGeoDataService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String buildSourcePayloadJson(
            Survey survey,
            String operationName,
            List<SurveyQuestion> questions,
            Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId,
            String existingSourcePayloadJson
    ) {
        ObjectNode root = parseObject(existingSourcePayloadJson);
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("generatedAt", OffsetDateTime.now().toString());
        preview.put("operationName", trimToNull(operationName));
        preview.put("surveyId", survey.getId() == null ? null : survey.getId().toString());
        preview.put("surveyName", trimToNull(survey.getName()));

        Survey contextualSurvey = cloneSurveyWithOperationContext(survey, operationName);
        ArrayNode questionSuggestions = preview.putArray("questionSuggestions");
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        List<NamedEntityEntry> politicalFallbackEntries = collectPoliticalFallbackEntries(contextualSurvey, questions, optionsByQuestionId);
        List<NamedEntityEntry> researchedPoliticalEntries = collectResearchedPoliticalEntries(contextualSurvey, questions);

        for (SurveyQuestion question : questions) {
            List<SurveyQuestionOption> options = optionsByQuestionId.getOrDefault(question.getId(), List.of());
            JsonNode autoEntityLexicon = buildPreviewLexicon(
                    contextualSurvey,
                    question,
                    options,
                    politicalFallbackEntries,
                    researchedPoliticalEntries
            );

            ObjectNode suggestion = objectMapper.createObjectNode();
            if (question.getId() != null) {
                suggestion.put("questionId", question.getId().toString());
            }
            suggestion.put("questionCode", trimToNull(question.getCode()));
            suggestion.put("questionTitle", trimToNull(question.getTitle()));
            suggestion.set("lexicon", autoEntityLexicon);
            questionSuggestions.add(suggestion);

            autoEntityLexicon.path("entries").forEach(entry -> {
                String label = trimToNull(entry.path("label").asText(null));
                if (label != null) {
                    keywords.add(label);
                }
                JsonNode aliasesNode = entry.get("aliases");
                if (aliasesNode != null && aliasesNode.isArray()) {
                    aliasesNode.forEach(aliasNode -> {
                        String alias = trimToNull(aliasNode.asText(null));
                        if (alias != null) {
                            keywords.add(alias);
                        }
                    });
                }
            });
        }

        ArrayNode keywordsNode = preview.putArray("keywords");
        keywords.forEach(keywordsNode::add);
        preview.put("keywordCount", keywords.size());
        root.set(PREVIEW_FIELD, preview);
        return serialize(root);
    }

    public List<String> extractKeywords(String sourcePayloadJson) {
        JsonNode preview = parseObject(sourcePayloadJson).path(PREVIEW_FIELD);
        JsonNode keywordsNode = preview.path("keywords");
        if (!keywordsNode.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        keywordsNode.forEach(node -> {
            String keyword = trimToNull(node.asText(null));
            if (keyword != null) {
                keywords.add(keyword);
            }
        });
        return List.copyOf(keywords);
    }

    public List<NamedEntityEntry> extractEntriesForQuestion(String sourcePayloadJson, UUID questionId, String questionCode) {
        JsonNode preview = parseObject(sourcePayloadJson).path(PREVIEW_FIELD);
        JsonNode suggestions = preview.path("questionSuggestions");
        if (!suggestions.isArray()) {
            return List.of();
        }

        for (JsonNode suggestion : suggestions) {
            String suggestionQuestionId = trimToNull(suggestion.path("questionId").asText(null));
            String suggestionQuestionCode = trimToNull(suggestion.path("questionCode").asText(null));
            if (!matchesQuestion(questionId, questionCode, suggestionQuestionId, suggestionQuestionCode)) {
                continue;
            }
            return parseEntries(suggestion.path("lexicon").path("entries"));
        }

        return List.of();
    }

    private boolean matchesQuestion(UUID questionId, String questionCode, String suggestionQuestionId, String suggestionQuestionCode) {
        if (questionId != null && suggestionQuestionId != null && questionId.toString().equals(suggestionQuestionId)) {
            return true;
        }
        return questionCode != null && suggestionQuestionCode != null && questionCode.equalsIgnoreCase(suggestionQuestionCode);
    }

    private List<NamedEntityEntry> parseEntries(JsonNode entriesNode) {
        if (!entriesNode.isArray()) {
            return List.of();
        }

        Map<String, NamedEntityEntry> merged = new LinkedHashMap<>();
        entriesNode.forEach(entry -> {
            String label = trimToNull(entry.path("label").asText(null));
            JsonNode aliasesNode = entry.get("aliases");
            if (label == null || aliasesNode == null || !aliasesNode.isArray()) {
                return;
            }

            LinkedHashSet<String> aliases = new LinkedHashSet<>();
            aliasesNode.forEach(aliasNode -> {
                String alias = trimToNull(aliasNode.asText(null));
                if (alias != null) {
                    aliases.add(alias);
                }
            });
            if (aliases.isEmpty()) {
                return;
            }

            String key = label.toLowerCase();
            if (merged.containsKey(key)) {
                aliases.addAll(merged.get(key).aliases());
            }
            merged.put(key, new NamedEntityEntry(label, List.copyOf(aliases)));
        });

        return List.copyOf(merged.values());
    }

    private JsonNode buildPreviewLexicon(
            Survey survey,
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            List<NamedEntityEntry> politicalFallbackEntries,
            List<NamedEntityEntry> researchedPoliticalEntries
    ) {
        if (isOccupationQuestion(survey, question)) {
            return buildOccupationLexicon();
        }

        String rebuiltSettings = surveyQuestionAutoLexiconService.rebuildSettingsJson(survey, question, options);
        JsonNode autoEntityLexicon = ensurePreviewLexicon(extractAutoEntityLexicon(rebuiltSettings));
        if (isPoliticalPersonQuestion(survey, question)
                && autoEntityLexicon.path("entries").isArray()
                && autoEntityLexicon.path("entries").isEmpty()) {
            List<NamedEntityEntry> mergedPoliticalEntries = mergeNamedEntityEntries(
                    researchedPoliticalEntries,
                    politicalFallbackEntries
            );
            if (!mergedPoliticalEntries.isEmpty()) {
                String source = !researchedPoliticalEntries.isEmpty()
                        ? "question_research"
                        : "survey_context";
                return buildNamedEntityLexicon("political", mergedPoliticalEntries, source);
            }
        }
        return autoEntityLexicon;
    }

    private List<NamedEntityEntry> mergeNamedEntityEntries(
            List<NamedEntityEntry> primaryEntries,
            List<NamedEntityEntry> secondaryEntries
    ) {
        Map<String, NamedEntityEntry> merged = new LinkedHashMap<>();
        if (primaryEntries != null) {
            primaryEntries.forEach(entry -> putNamedEntityEntry(merged, entry));
        }
        if (secondaryEntries != null) {
            secondaryEntries.forEach(entry -> putNamedEntityEntry(merged, entry));
        }
        return List.copyOf(merged.values());
    }

    private List<NamedEntityEntry> collectPoliticalFallbackEntries(
            Survey survey,
            List<SurveyQuestion> questions,
            Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId
    ) {
        Map<String, NamedEntityEntry> merged = new LinkedHashMap<>();
        for (SurveyQuestion question : questions) {
            List<SurveyQuestionOption> options = optionsByQuestionId.getOrDefault(question.getId(), List.of());
            String rebuiltSettings = surveyQuestionAutoLexiconService.rebuildSettingsJson(survey, question, options);
            JsonNode autoEntityLexicon = extractAutoEntityLexicon(rebuiltSettings);
            if (autoEntityLexicon == null || !"political".equalsIgnoreCase(trimToNull(autoEntityLexicon.path("domain").asText(null)))) {
                continue;
            }
            parseEntries(autoEntityLexicon.path("entries")).stream()
                    .filter(this::isPersonEntryCandidate)
                    .forEach(entry -> putNamedEntityEntry(merged, entry));
        }
        return List.copyOf(merged.values());
    }

    private List<NamedEntityEntry> collectResearchedPoliticalEntries(Survey survey, List<SurveyQuestion> questions) {
        if (turkeyGeoDataService == null) {
            return List.of();
        }
        if (questions.stream().noneMatch(question -> isPoliticalPersonQuestion(survey, question))) {
            return List.of();
        }

        String context = buildSurveyContext(survey, questions);
        if (!containsAnyPhrase(normalize(context), POLITICAL_HINTS)) {
            return List.of();
        }

        Map<String, NamedEntityEntry> merged = new LinkedHashMap<>();
        for (String cityCode : turkeyGeoDataService.detectCityCodes(context)) {
            String cityName = turkeyGeoDataService.getCities().stream()
                    .filter(city -> city.code().equals(cityCode))
                    .map(TurkeyGeoDataService.CityEntry::name)
                    .findFirst()
                    .orElse(null);
            if (cityName == null) {
                continue;
            }
            fetchCurrentMayor(cityName).ifPresent(entry -> putNamedEntityEntry(merged, entry));
            fetchCurrentMembersOfParliament(cityName).forEach(entry -> putNamedEntityEntry(merged, entry));
        }
        return List.copyOf(merged.values());
    }

    private String buildSurveyContext(Survey survey, List<SurveyQuestion> questions) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, survey == null ? null : survey.getName());
        addIfPresent(parts, survey == null ? null : survey.getDescription());
        for (SurveyQuestion question : questions) {
            addIfPresent(parts, question.getCode());
            addIfPresent(parts, question.getTitle());
            addIfPresent(parts, question.getDescription());
        }
        return String.join(" ", parts);
    }

    private void putNamedEntityEntry(Map<String, NamedEntityEntry> target, NamedEntityEntry incoming) {
        String key = normalize(incoming.label());
        if (key.isBlank()) {
            return;
        }
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (target.containsKey(key)) {
            aliases.addAll(target.get(key).aliases());
        }
        aliases.addAll(incoming.aliases());
        target.put(key, new NamedEntityEntry(incoming.label(), List.copyOf(aliases)));
    }

    private JsonNode buildOccupationLexicon() {
        List<NamedEntityEntry> entries = new ArrayList<>();
        for (OccupationSeed seed : OCCUPATION_SEEDS) {
            LinkedHashSet<String> aliases = new LinkedHashSet<>();
            seed.aliases().forEach(alias -> {
                String value = trimToNull(alias);
                if (value != null) {
                    aliases.add(value);
                }
            });
            entries.add(new NamedEntityEntry(seed.label(), List.copyOf(aliases)));
        }
        return buildNamedEntityLexicon("occupation", entries, "occupation_dictionary");
    }

    private JsonNode buildNamedEntityLexicon(String domain, List<NamedEntityEntry> entries, String source) {
        ObjectNode lexiconNode = objectMapper.createObjectNode();
        lexiconNode.put("type", "named_entity");
        lexiconNode.put("domain", domain);
        lexiconNode.put("generatedAt", OffsetDateTime.now().toString());
        lexiconNode.put("source", source);
        ArrayNode entriesNode = lexiconNode.putArray("entries");
        for (NamedEntityEntry entry : entries) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("label", entry.label());
            ArrayNode aliasesNode = item.putArray("aliases");
            entry.aliases().forEach(aliasesNode::add);
            entriesNode.add(item);
        }
        return lexiconNode;
    }

    private java.util.Optional<NamedEntityEntry> fetchCurrentMayor(String cityName) {
        String wikitext = fetchWikipediaWikitext(cityName + " belediye baskanlari listesi");
        if (wikitext == null) {
            return java.util.Optional.empty();
        }
        Matcher matcher = CURRENT_MAYOR_PATTERN.matcher(normalizeWikiText(wikitext));
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        String label = trimToNull(matcher.group(2) != null ? matcher.group(2) : matcher.group(1));
        return label == null ? java.util.Optional.empty() : java.util.Optional.of(buildNamedEntityEntry(label));
    }

    private List<NamedEntityEntry> fetchCurrentMembersOfParliament(String cityName) {
        String wikitext = fetchWikipediaWikitext(cityName + " milletvekilleri listesi");
        if (wikitext == null) {
            return List.of();
        }

        Matcher matcher = TERM_BLOCK_PATTERN.matcher(normalizeWikiText(wikitext));
        int bestTerm = -1;
        String latestBlock = null;
        while (matcher.find()) {
            int term = Integer.parseInt(matcher.group(1));
            if (term >= bestTerm) {
                bestTerm = term;
                latestBlock = matcher.group(2);
            }
        }
        if (latestBlock == null) {
            return List.of();
        }

        Map<String, NamedEntityEntry> merged = new LinkedHashMap<>();
        for (String line : latestBlock.split("\\R")) {
            String trimmed = trimToNull(line);
            if (trimmed == null || !trimmed.startsWith("|")) {
                continue;
            }
            Matcher linkMatcher = WIKI_LINK_PATTERN.matcher(trimmed);
            if (!linkMatcher.find()) {
                continue;
            }
            String rawTarget = trimToNull(linkMatcher.group(1));
            String rawLabel = trimToNull(linkMatcher.group(2));
            String label = trimToNull(rawLabel != null ? rawLabel : rawTarget);
            if (label == null || !looksLikePersonLabel(label)) {
                continue;
            }
            putNamedEntityEntry(merged, buildNamedEntityEntry(label));
        }
        return List.copyOf(merged.values());
    }

    private NamedEntityEntry buildNamedEntityEntry(String label) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        addAlias(aliases, label);
        String simplified = label.replaceAll("\\s*\\([^)]*\\)", "").trim();
        addAlias(aliases, simplified);
        List<String> tokens = List.of(simplified.split("\\s+"));
        if (tokens.size() >= 2) {
            addAlias(aliases, tokens.getFirst() + " " + tokens.getLast());
            addAlias(aliases, tokens.getLast());
        }
        if (tokens.size() >= 3) {
            addAlias(aliases, tokens.get(tokens.size() - 2) + " " + tokens.getLast());
        }
        return new NamedEntityEntry(label, List.copyOf(aliases));
    }

    private void addAlias(LinkedHashSet<String> aliases, String value) {
        String sanitized = trimToNull(value);
        if (sanitized != null) {
            aliases.add(sanitized);
        }
    }

    private boolean looksLikePersonLabel(String label) {
        String normalized = normalize(label);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.contains("parti") || normalized.contains("tbmm") || normalized.contains("donem")) {
            return false;
        }
        return normalized.split("\\s+").length >= 2;
    }

    private boolean isPersonEntryCandidate(NamedEntityEntry entry) {
        if (entry == null) {
            return false;
        }
        String label = trimToNull(entry.label());
        if (label == null) {
            return false;
        }
        String normalized = normalize(label);
        if (normalized.isBlank()
                || normalized.startsWith("option ")
                || normalized.startsWith("option_")
                || containsAnyPhrase(normalized, NON_PERSON_POLITICAL_LABEL_HINTS)) {
            return false;
        }
        return looksLikePersonLabel(label);
    }

    protected String fetchWikipediaWikitext(String title) {
        try {
            String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://tr.wikipedia.org/w/api.php?action=query&prop=revisions&rvprop=content&format=json&titles=" + encodedTitle))
                    .timeout(Duration.ofSeconds(3))
                    .header("User-Agent", "SurveyAI/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            JsonNode pages = objectMapper.readTree(response.body()).path("query").path("pages");
            if (!pages.isObject()) {
                return null;
            }
            for (JsonNode page : iterable(pages)) {
                JsonNode revisions = page.path("revisions");
                if (!revisions.isArray() || revisions.isEmpty()) {
                    continue;
                }
                String content = trimToNull(revisions.get(0).path("*").asText(null));
                if (content != null) {
                    return content;
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Iterable<JsonNode> iterable(JsonNode objectNode) {
        List<JsonNode> values = new ArrayList<>();
        objectNode.elements().forEachRemaining(values::add);
        return values;
    }

    private String normalizeWikiText(String value) {
        String sanitized = Objects.requireNonNullElse(value, "");
        return sanitized
                .replace('\u0131', 'i')
                .replace('\u0130', 'I')
                .replace('\u015f', 's')
                .replace('\u015e', 'S')
                .replace('\u011f', 'g')
                .replace('\u011e', 'G')
                .replace('\u00fc', 'u')
                .replace('\u00dc', 'U')
                .replace('\u00f6', 'o')
                .replace('\u00d6', 'O')
                .replace('\u00e7', 'c')
                .replace('\u00c7', 'C');
    }

    private JsonNode ensurePreviewLexicon(JsonNode autoEntityLexicon) {
        if (autoEntityLexicon != null && autoEntityLexicon.isObject()) {
            ObjectNode copy = autoEntityLexicon.deepCopy();
            if (!copy.path("entries").isArray()) {
                copy.set("entries", objectMapper.createArrayNode());
            }
            if (trimToNull(copy.path("type").asText(null)) == null) {
                copy.put("type", "named_entity");
            }
            if (trimToNull(copy.path("domain").asText(null)) == null) {
                copy.put("domain", "generic");
            }
            return copy;
        }

        ObjectNode emptyLexicon = objectMapper.createObjectNode();
        emptyLexicon.put("type", "named_entity");
        emptyLexicon.put("domain", "generic");
        emptyLexicon.put("generatedAt", OffsetDateTime.now().toString());
        emptyLexicon.set("entries", objectMapper.createArrayNode());
        return emptyLexicon;
    }

    private JsonNode extractAutoEntityLexicon(String rebuiltSettingsJson) {
        try {
            return objectMapper.readTree(rebuiltSettingsJson).path("autoEntityLexicon").deepCopy();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Survey cloneSurveyWithOperationContext(Survey survey, String operationName) {
        Survey contextualSurvey = new Survey();
        contextualSurvey.setId(survey.getId());
        contextualSurvey.setLanguageCode(survey.getLanguageCode());
        contextualSurvey.setIntroPrompt(survey.getIntroPrompt());
        contextualSurvey.setClosingPrompt(survey.getClosingPrompt());
        contextualSurvey.setStatus(survey.getStatus());
        contextualSurvey.setMaxRetryPerQuestion(survey.getMaxRetryPerQuestion());
        contextualSurvey.setName(joinDistinct(survey.getName(), operationName));
        contextualSurvey.setDescription(joinDistinct(survey.getDescription(), operationName));
        contextualSurvey.setSourcePayloadJson(survey.getSourcePayloadJson());
        return contextualSurvey;
    }

    // Only the question itself decides. With the survey name and description included, a survey about
    // occupations gave every question, choice questions too, the occupation dictionary.
    private boolean isOccupationQuestion(Survey survey, SurveyQuestion question) {
        return containsAnyPhrase(buildQuestionContext(null, question), OCCUPATION_HINTS);
    }

    private boolean isPoliticalPersonQuestion(Survey survey, SurveyQuestion question) {
        String context = buildQuestionContext(survey, question);
        return containsAnyPhrase(context, POLITICAL_HINTS) && containsAnyPhrase(context, PERSON_OPEN_ENDED_HINTS);
    }

    private String buildQuestionContext(Survey survey, SurveyQuestion question) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, survey == null ? null : survey.getName());
        addIfPresent(parts, survey == null ? null : survey.getDescription());
        addIfPresent(parts, question == null ? null : question.getCode());
        addIfPresent(parts, question == null ? null : question.getTitle());
        addIfPresent(parts, question == null ? null : question.getDescription());
        return normalize(String.join(" ", parts));
    }

    private boolean containsAnyPhrase(String haystack, Collection<String> phrases) {
        for (String phrase : phrases) {
            String normalizedPhrase = normalize(phrase);
            if (!normalizedPhrase.isBlank() && haystack.contains(normalizedPhrase)) {
                return true;
            }
        }
        return false;
    }

    private void addIfPresent(List<String> parts, String value) {
        String sanitized = trimToNull(value);
        if (sanitized != null) {
            parts.add(sanitized);
        }
    }

    private String normalize(String value) {
        String sanitized = trimToNull(value);
        if (sanitized == null) {
            return "";
        }
        return sanitized.toLowerCase(Locale.ROOT)
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
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String joinDistinct(String first, String second) {
        String left = trimToNull(first);
        String right = trimToNull(second);
        if (left == null) {
            return right;
        }
        if (right == null || left.equalsIgnoreCase(right)) {
            return left;
        }
        return left + " " + right;
    }

    private ObjectNode parseObject(String rawJson) {
        String trimmed = trimToNull(rawJson);
        if (trimmed == null) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(trimmed);
            return parsed != null && parsed.isObject() ? (ObjectNode) parsed.deepCopy() : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String serialize(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record NamedEntityEntry(
            String label,
            List<String> aliases
    ) {
    }

    private record OccupationSeed(
            String label,
            List<String> aliases
    ) {
    }
}
