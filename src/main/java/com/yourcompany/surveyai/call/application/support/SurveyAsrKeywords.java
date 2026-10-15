package com.yourcompany.surveyai.call.application.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.operation.application.support.OperationAutoEntityLexiconService;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Words the speech recognizer should expect on a survey's calls: the names and aliases in each
// question's entity lexicon and in the operation's imported data.
public final class SurveyAsrKeywords {

    private SurveyAsrKeywords() {
    }

    public static List<String> collect(
            Survey survey,
            Operation operation,
            ObjectMapper objectMapper,
            OperationAutoEntityLexiconService operationAutoEntityLexiconService
    ) {
        Set<String> keywords = new LinkedHashSet<>();
        for (SurveyQuestion question : survey.getQuestions()) {
            String settingsJson = question.getSettingsJson();
            if (settingsJson == null || settingsJson.isBlank()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(settingsJson);
                JsonNode entriesNode = root.path("autoEntityLexicon").path("entries");
                if (!entriesNode.isArray()) {
                    continue;
                }
                entriesNode.forEach(entry -> {
                    String label = entry.path("label").asText(null);
                    if (label != null && !label.isBlank()) {
                        keywords.add(label.trim());
                    }
                    JsonNode aliases = entry.get("aliases");
                    if (aliases != null && aliases.isArray()) {
                        aliases.forEach(a -> {
                            String alias = a.asText(null);
                            if (alias != null && !alias.isBlank()) {
                                keywords.add(alias.trim());
                            }
                        });
                    }
                });
            } catch (Exception ignored) {
                // malformed settingsJson — skip
            }
        }
        keywords.addAll(operationAutoEntityLexiconService.extractKeywords(operation.getSourcePayloadJson()));
        return new ArrayList<>(keywords);
    }
}
