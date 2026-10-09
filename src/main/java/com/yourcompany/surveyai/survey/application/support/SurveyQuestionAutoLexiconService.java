package com.yourcompany.surveyai.survey.application.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourcompany.surveyai.survey.application.support.TurkeyGeoDataService.CityEntry;
import com.yourcompany.surveyai.survey.application.support.TurkeyGeoDataService.DistrictEntry;
import com.yourcompany.surveyai.survey.application.support.TurkeyGeoDataService.GeoGranularity;
import com.yourcompany.surveyai.survey.application.support.TurkeyGeoDataService.GeoScope;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SurveyQuestionAutoLexiconService {

    private static final Set<String> CITY_HINTS = Set.of("sehir", "şehir", "city", "province");
    // "il" and the vowel-dropping forms of "şehir" are too short or irregular for prefix matching
    // ("ilgili", "bilgi", "aile"), so only these whole words count.
    private static final Set<String> CITY_WORD_HINTS = Set.of(
            "il", "ili", "ilde", "ilin", "iline", "ilinde", "iliniz", "ilinizi", "ilinizde", "ilimiz", "ilimizde",
            "sehri", "sehrin", "sehrinde", "sehriniz", "sehrinizi", "sehrinizde"
    );
    private static final Set<String> DISTRICT_HINTS = Set.of("ilce", "ilçe", "district");
    private static final Set<String> NEIGHBOURHOOD_HINTS = Set.of("mahalle", "mahallesi", "neighborhood", "neighbourhood", "semt");
    private static final Set<String> LOCATION_QUESTION_WORDS = Set.of(
            "hangi", "nerede", "nerde", "neresi", "yasiyorsunuz", "yasadiginiz", "oturuyorsunuz", "oturdugunuz",
            "ikamet", "where", "which", "live", "reside"
    );
    private static final Set<String> POLITICAL_HINTS = Set.of(
            "siyasi", "politika", "secim", "seçim", "aday", "parti", "belediye", "baskan", "başkan",
            "milletvekili", "cumhurbaskani", "cumhurbaşkanı", "oy", "tercih", "destek"
    );
    private static final Set<String> ENTITY_STOPWORDS = Set.of(
            "evet", "hayir", "hayır", "bilmiyorum", "kararsizim", "kararsızım", "diger", "diğer", "none"
    );
    private static final Set<String> TITLE_TOKENS = Set.of(
            "sn", "sayin", "sayın", "dr", "prof", "doc", "doç", "av", "baskan", "başkan"
    );

    private final ObjectMapper objectMapper;
    private final TurkeyGeoDataService turkeyGeoDataService;

    public SurveyQuestionAutoLexiconService(
            ObjectMapper objectMapper,
            TurkeyGeoDataService turkeyGeoDataService
    ) {
        this.objectMapper = objectMapper;
        this.turkeyGeoDataService = turkeyGeoDataService;
    }

    public String rebuildSettingsJson(SurveyQuestion question, List<SurveyQuestionOption> options) {
        return rebuildSettingsJson(question.getSurvey(), question, options);
    }

    public String rebuildSettingsJson(Survey survey, SurveyQuestion question, List<SurveyQuestionOption> options) {
        ObjectNode root = parseSettings(question.getSettingsJson());
        root.remove("autoLexicon");
        root.remove("autoAliases");
        root.remove("autoEntityLexicon");

        ObjectNode aliasesNode = objectMapper.createObjectNode();

        GeoScope geoScope = inferGeoScope(survey, question, options);
        if (geoScope != null) {
            root.set("autoLexicon", buildGeoLexiconNode(geoScope));
            mergeAliases(aliasesNode, buildGeoAliasesNode(geoScope, options));
        }

        ObjectNode entityLexiconNode = buildEntityLexiconNode(survey, question, options);
        if (entityLexiconNode != null) {
            root.set("autoEntityLexicon", entityLexiconNode);
            mergeAliases(aliasesNode, buildEntityAliasesNode(entityLexiconNode, options));
        }

        if (!aliasesNode.isEmpty()) {
            root.set("autoAliases", aliasesNode);
        }
        return serialize(root);
    }

    private GeoScope inferGeoScope(Survey survey, SurveyQuestion question, List<SurveyQuestionOption> options) {
        GeoGranularity granularity = inferGeoGranularity(question);
        if (granularity == null) {
            return null;
        }

        String combinedText = buildCombinedContext(survey, question, options);
        Set<String> cityCodes = new LinkedHashSet<>(turkeyGeoDataService.detectCityCodes(combinedText));
        List<DistrictEntry> matchedDistricts = turkeyGeoDataService.detectDistricts(combinedText, cityCodes);
        if (cityCodes.isEmpty() && !matchedDistricts.isEmpty()) {
            matchedDistricts.stream()
                    .map(DistrictEntry::cityCode)
                    .filter(Objects::nonNull)
                    .forEach(cityCodes::add);
        }

        Set<String> districtNames = new LinkedHashSet<>();
        matchedDistricts.forEach(district -> districtNames.add(district.name()));

        if (granularity == GeoGranularity.NEIGHBOURHOOD && cityCodes.isEmpty() && districtNames.isEmpty()) {
            return null;
        }

        return new GeoScope(granularity, Set.copyOf(cityCodes), Set.copyOf(districtNames));
    }

    // The granularity comes from the question itself, not the survey name or intro, and only when the
    // question asks for a place. Otherwise "Ürünümüzle ilgili önerileriniz" or "İlçenizin en önemli
    // sorunu nedir?" gets a geo scope and a place mentioned in the answer replaces the whole answer.
    private GeoGranularity inferGeoGranularity(SurveyQuestion question) {
        List<String> titleWords = words(question.getTitle());
        List<String> questionWords = words(String.join(" ",
                Objects.toString(question.getCode(), ""),
                Objects.toString(question.getTitle(), ""),
                Objects.toString(question.getDescription(), ""),
                Objects.toString(question.getRetryPrompt(), "")
        ));

        // A bare field label such as "İl" or "İlçe adı" also asks for a place.
        boolean asksForPlace = titleWords.size() <= 2 || questionWords.stream().anyMatch(LOCATION_QUESTION_WORDS::contains);
        if (!asksForPlace) {
            return null;
        }
        if (containsHintWord(questionWords, NEIGHBOURHOOD_HINTS, Set.of())) {
            return GeoGranularity.NEIGHBOURHOOD;
        }
        if (containsHintWord(questionWords, DISTRICT_HINTS, Set.of())) {
            return GeoGranularity.DISTRICT;
        }
        if (containsHintWord(questionWords, CITY_HINTS, CITY_WORD_HINTS)) {
            return GeoGranularity.CITY;
        }
        return null;
    }

    private List<String> words(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? List.of() : List.of(normalized.split(" "));
    }

    // Prefix hints also match suffixed forms ("ilcenizde", "mahallesi"); word hints must be a whole word.
    private boolean containsHintWord(List<String> words, Collection<String> prefixHints, Collection<String> wordHints) {
        for (String word : words) {
            for (String hint : prefixHints) {
                String normalizedHint = normalize(hint);
                if (!normalizedHint.isBlank() && word.startsWith(normalizedHint)) {
                    return true;
                }
            }
            for (String hint : wordHints) {
                if (word.equals(normalize(hint))) {
                    return true;
                }
            }
        }
        return false;
    }

    private ObjectNode buildGeoLexiconNode(GeoScope scope) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "geo");
        node.put("granularity", scope.granularity().name());
        node.put("generatedAt", OffsetDateTime.now().toString());
        ArrayNode cityCodesNode = node.putArray("cityCodes");
        scope.cityCodes().forEach(cityCodesNode::add);
        ArrayNode districtNamesNode = node.putArray("districtNames");
        scope.districtNames().forEach(districtNamesNode::add);
        return node;
    }

    private ObjectNode buildGeoAliasesNode(GeoScope scope, List<SurveyQuestionOption> options) {
        ObjectNode aliasesNode = objectMapper.createObjectNode();
        if (options == null || options.isEmpty()) {
            return aliasesNode;
        }

        for (SurveyQuestionOption option : options) {
            if (option == null || !option.isActive()) {
                continue;
            }

            Set<String> aliases = switch (scope.granularity()) {
                case CITY -> resolveCityAliases(option);
                case DISTRICT -> resolveDistrictAliases(option, scope.cityCodes());
                case NEIGHBOURHOOD -> resolveNeighbourhoodAliases(option, scope);
            };
            if (aliases.isEmpty()) {
                continue;
            }
            addAliasArray(aliasesNode, option.getOptionCode(), aliases);
            addAliasArray(aliasesNode, option.getLabel(), aliases);
        }
        return aliasesNode;
    }

    private ObjectNode buildEntityLexiconNode(Survey survey, SurveyQuestion question, List<SurveyQuestionOption> options) {
        String combinedText = buildCombinedContext(survey, question, options);
        String normalizedContext = normalize(combinedText);
        boolean political = containsAnyKeyword(normalizedContext, POLITICAL_HINTS);

        Map<String, EntityEntry> entries = new LinkedHashMap<>();
        if (options != null) {
            for (SurveyQuestionOption option : options) {
                if (option == null || !option.isActive() || !isNamedEntityLikeOption(option, political)) {
                    continue;
                }
                String label = option.getLabel().trim();
                Set<String> aliases = buildEntityAliases(label, option.getValue(), option.getOptionCode());
                if (!aliases.isEmpty()) {
                    putEntityEntry(entries, new EntityEntry(label, aliases));
                }
            }
        }

        if (political) {
            extractInlineEntities(question.getTitle()).forEach(name -> putEntityEntry(entries, new EntityEntry(name, buildEntityAliases(name, null, null))));
            extractInlineEntities(question.getDescription()).forEach(name -> putEntityEntry(entries, new EntityEntry(name, buildEntityAliases(name, null, null))));
        }

        if (entries.isEmpty()) {
            return null;
        }

        ObjectNode lexiconNode = objectMapper.createObjectNode();
        lexiconNode.put("type", "named_entity");
        lexiconNode.put("domain", political ? "political" : "generic");
        lexiconNode.put("generatedAt", OffsetDateTime.now().toString());
        ArrayNode entriesNode = lexiconNode.putArray("entries");
        for (EntityEntry entry : entries.values()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("label", entry.label());
            ArrayNode aliasesNode = item.putArray("aliases");
            entry.aliases().forEach(aliasesNode::add);
            entriesNode.add(item);
        }
        return lexiconNode;
    }

    private void putEntityEntry(Map<String, EntityEntry> entries, EntityEntry incoming) {
        String key = normalize(incoming.label());
        if (key.isBlank()) {
            return;
        }
        Set<String> merged = new LinkedHashSet<>(entries.containsKey(key) ? entries.get(key).aliases() : Set.of());
        merged.addAll(incoming.aliases());
        entries.put(key, new EntityEntry(incoming.label(), Set.copyOf(merged)));
    }

    private ObjectNode buildEntityAliasesNode(ObjectNode entityLexiconNode, List<SurveyQuestionOption> options) {
        ObjectNode aliasesNode = objectMapper.createObjectNode();
        if (options == null || options.isEmpty()) {
            return aliasesNode;
        }

        Map<String, Set<String>> aliasesByNormalizedLabel = new LinkedHashMap<>();
        JsonNode entriesNode = entityLexiconNode.path("entries");
        if (entriesNode.isArray()) {
            entriesNode.forEach(entry -> {
                String label = entry.path("label").asText(null);
                JsonNode aliases = entry.get("aliases");
                if (label == null || aliases == null || !aliases.isArray()) {
                    return;
                }
                Set<String> values = new LinkedHashSet<>();
                aliases.forEach(item -> values.add(item.asText()));
                aliasesByNormalizedLabel.put(normalize(label), Set.copyOf(values));
            });
        }

        for (SurveyQuestionOption option : options) {
            if (option == null || !option.isActive()) {
                continue;
            }
            Set<String> aliases = aliasesByNormalizedLabel.get(normalize(option.getLabel()));
            if (aliases == null || aliases.isEmpty()) {
                continue;
            }
            addAliasArray(aliasesNode, option.getOptionCode(), aliases);
            addAliasArray(aliasesNode, option.getLabel(), aliases);
        }
        return aliasesNode;
    }

    private Set<String> resolveCityAliases(SurveyQuestionOption option) {
        String normalizedOption = normalize(option.getLabel() + " " + option.getValue() + " " + option.getOptionCode());
        for (CityEntry city : turkeyGeoDataService.getCities()) {
            if (city.variants().stream().anyMatch(variant -> containsPhrase(normalizedOption, variant))) {
                return city.variants();
            }
        }
        return Set.of();
    }

    private Set<String> resolveDistrictAliases(SurveyQuestionOption option, Set<String> cityCodes) {
        String normalizedOption = normalize(option.getLabel() + " " + option.getValue() + " " + option.getOptionCode());
        List<DistrictEntry> districts = turkeyGeoDataService.detectDistricts(normalizedOption, cityCodes);
        if (districts.isEmpty()) {
            return Set.of();
        }
        return districts.getFirst().variants();
    }

    private Set<String> resolveNeighbourhoodAliases(SurveyQuestionOption option, GeoScope scope) {
        TurkeyGeoDataService.GeoMatchResult match = turkeyGeoDataService.match(option.getLabel() + " " + option.getValue(), scope);
        if (match.candidate() == null) {
            return Set.of();
        }
        return match.candidate().variants();
    }

    private boolean isNamedEntityLikeOption(SurveyQuestionOption option, boolean politicalContext) {
        String label = option.getLabel() == null ? "" : option.getLabel().trim();
        String normalizedLabel = normalize(label);
        if (normalizedLabel.isBlank() || ENTITY_STOPWORDS.contains(normalizedLabel)) {
            return false;
        }
        if (normalizedLabel.matches("^[0-9\\s\\-_/]+$")) {
            return false;
        }
        if (politicalContext) {
            return true;
        }
        return label.matches(".*[A-ZÇĞİÖŞÜ].*[A-ZÇĞİÖŞÜ].*")
                || label.matches("^[A-ZÇĞİÖŞÜ]{2,8}$")
                || label.split("\\s+").length >= 2;
    }

    private Set<String> buildEntityAliases(String label, String value, String optionCode) {
        Set<String> aliases = new LinkedHashSet<>();
        addEntityAliasVariants(aliases, label);
        addEntityAliasVariants(aliases, value);
        addEntityAliasVariants(aliases, optionCode);
        return Set.copyOf(aliases);
    }

    private void addEntityAliasVariants(Set<String> aliases, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return;
        }
        String trimmed = rawValue.trim();
        aliases.add(trimmed);

        String withoutParentheses = trimmed.replaceAll("\\s*\\([^)]*\\)", "").trim();
        if (!withoutParentheses.isBlank()) {
            aliases.add(withoutParentheses);
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\(([^)]+)\\)").matcher(trimmed);
        while (matcher.find()) {
            String group = matcher.group(1).trim();
            if (!group.isBlank()) {
                aliases.add(group);
            }
        }

        List<String> tokens = tokenizeEntity(trimmed);
        if (tokens.size() >= 2) {
            aliases.add(String.join(" ", tokens));
            aliases.add(tokens.getFirst() + " " + tokens.getLast());
            aliases.add(tokens.getLast());
        }
        if (tokens.size() >= 3) {
            aliases.add(tokens.get(tokens.size() - 2) + " " + tokens.getLast());
            aliases.add(tokens.getFirst() + " " + tokens.get(1));
        }

        String compactAcronym = trimmed.replaceAll("[^A-ZÇĞİÖŞÜ]", "");
        if (compactAcronym.length() >= 2 && compactAcronym.length() <= 8) {
            aliases.add(compactAcronym);
        }
    }

    private List<String> tokenizeEntity(String rawValue) {
        List<String> tokens = new ArrayList<>();
        for (String token : rawValue.replaceAll("[()]", " ").split("\\s+")) {
            String normalized = normalize(token);
            if (normalized.isBlank() || TITLE_TOKENS.contains(normalized)) {
                continue;
            }
            tokens.add(token.trim());
        }
        return tokens;
    }

    private List<String> extractInlineEntities(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> entities = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b([A-ZÇĞİÖŞÜ][a-zçğıöşüA-ZÇĞİÖŞÜ]+(?:\\s+[A-ZÇĞİÖŞÜ][a-zçğıöşüA-ZÇĞİÖŞÜ]+){1,3})\\b")
                .matcher(value);
        while (matcher.find()) {
            String entity = matcher.group(1).trim();
            if (!entity.isBlank()) {
                entities.add(entity);
            }
        }
        return entities;
    }

    private String buildCombinedContext(Survey survey, SurveyQuestion question, List<SurveyQuestionOption> options) {
        List<String> parts = new ArrayList<>();
        if (survey != null) {
            addIfPresent(parts, survey.getName());
            addIfPresent(parts, survey.getDescription());
            addIfPresent(parts, survey.getIntroPrompt());
        }
        addIfPresent(parts, question.getCode());
        addIfPresent(parts, question.getTitle());
        addIfPresent(parts, question.getDescription());
        addIfPresent(parts, question.getRetryPrompt());
        if (options != null) {
            for (SurveyQuestionOption option : options) {
                addIfPresent(parts, option.getOptionCode());
                addIfPresent(parts, option.getLabel());
                addIfPresent(parts, option.getValue());
            }
        }
        return String.join(" ", parts);
    }

    private boolean containsAnyKeyword(String normalizedText, Collection<String> keywords) {
        for (String keyword : keywords) {
            String normalizedKeyword = normalize(keyword);
            if (!normalizedKeyword.isBlank() && normalizedText.contains(normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPhrase(String haystack, String needle) {
        if (haystack == null || needle == null || haystack.isBlank() || needle.isBlank()) {
            return false;
        }
        return (" " + haystack + " ").contains(" " + needle + " ");
    }

    private void addAliasArray(ObjectNode node, String key, Set<String> aliases) {
        if (key == null || key.isBlank() || aliases.isEmpty()) {
            return;
        }
        ArrayNode arrayNode = objectMapper.createArrayNode();
        aliases.forEach(arrayNode::add);
        node.set(key, arrayNode);
    }

    private void mergeAliases(ObjectNode target, ObjectNode additions) {
        additions.fields().forEachRemaining(entry -> {
            Set<String> merged = new LinkedHashSet<>();
            if (target.has(entry.getKey()) && target.get(entry.getKey()).isArray()) {
                target.get(entry.getKey()).forEach(item -> merged.add(item.asText()));
            }
            entry.getValue().forEach(item -> merged.add(item.asText()));
            ArrayNode arrayNode = objectMapper.createArrayNode();
            merged.forEach(arrayNode::add);
            target.set(entry.getKey(), arrayNode);
        });
    }

    private ObjectNode parseSettings(String settingsJson) {
        String trimmed = settingsJson == null ? null : settingsJson.trim();
        if (trimmed == null || trimmed.isEmpty() || "{}".equals(trimmed)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            return node != null && node.isObject() ? (ObjectNode) node.deepCopy() : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String serialize(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            return "{}";
        }
    }

    private void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
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
                .replace('\u00c7', 'c');
        String decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private record EntityEntry(
            String label,
            Set<String> aliases
    ) {
    }
}
