package com.yourcompany.surveyai.operation.application.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.survey.application.support.SurveyQuestionAutoLexiconService;
import com.yourcompany.surveyai.survey.application.support.TurkeyGeoDataService;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationAutoEntityLexiconServiceTest {

    @Test
    void buildsOperationScopedPoliticalLexiconFromOperationName() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TurkeyGeoDataService geoDataService = new TurkeyGeoDataService(objectMapper);
        var loadMethod = TurkeyGeoDataService.class.getDeclaredMethod("load");
        loadMethod.setAccessible(true);
        loadMethod.invoke(geoDataService);
        SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService =
                new SurveyQuestionAutoLexiconService(objectMapper, geoDataService);
        OperationAutoEntityLexiconService service =
                new OperationAutoEntityLexiconService(objectMapper, surveyQuestionAutoLexiconService, geoDataService);

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setName("Aday arastirmasi");

        SurveyQuestion question = new SurveyQuestion();
        question.setId(UUID.randomUUID());
        question.setCode("vote");
        question.setTitle("Kimi destekliyorsunuz?");
        question.setSettingsJson("{}");

        SurveyQuestion secondQuestion = new SurveyQuestion();
        secondQuestion.setId(UUID.randomUUID());
        secondQuestion.setCode("reason");
        secondQuestion.setTitle("Neden boyle dusunuyorsunuz?");
        secondQuestion.setSettingsJson("{}");

        SurveyQuestionOption option = new SurveyQuestionOption();
        option.setActive(true);
        option.setOptionCode("cemil_tugay");
        option.setLabel("Cemil Tugay");
        option.setValue("Cemil Tugay");
        option.setSurveyQuestion(question);

        String payload = service.buildSourcePayloadJson(
                survey,
                "Izmir belediye secimleri",
                List.of(question, secondQuestion),
                Map.of(question.getId(), List.of(option), secondQuestion.getId(), List.of()),
                null
        );

        JsonNode preview = objectMapper.readTree(payload).path("autoEntityLexiconPreview");
        assertEquals("Izmir belediye secimleri", preview.path("operationName").asText());
        assertEquals(2, preview.path("questionSuggestions").size());
        assertEquals("political", preview.path("questionSuggestions").get(0).path("lexicon").path("domain").asText());
        assertEquals(0, preview.path("questionSuggestions").get(1).path("lexicon").path("entries").size());
        assertTrue(preview.path("keywords").toString().contains("Cemil Tugay"));
    }

    @Test
    void buildsOccupationLexiconPreviewForOccupationQuestions() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TurkeyGeoDataService geoDataService = new TurkeyGeoDataService(objectMapper);
        var loadMethod = TurkeyGeoDataService.class.getDeclaredMethod("load");
        loadMethod.setAccessible(true);
        loadMethod.invoke(geoDataService);
        SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService =
                new SurveyQuestionAutoLexiconService(objectMapper, geoDataService);
        OperationAutoEntityLexiconService service =
                new OperationAutoEntityLexiconService(objectMapper, surveyQuestionAutoLexiconService, geoDataService);

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setName("Secmen profili");

        SurveyQuestion question = new SurveyQuestion();
        question.setId(UUID.randomUUID());
        question.setCode("occupation");
        question.setTitle("Mesleginiz nedir?");
        question.setSettingsJson("{}");

        String payload = service.buildSourcePayloadJson(
                survey,
                "Izmir belediye secimleri",
                List.of(question),
                Map.of(question.getId(), List.of()),
                null
        );

        JsonNode lexicon = objectMapper.readTree(payload)
                .path("autoEntityLexiconPreview")
                .path("questionSuggestions")
                .get(0)
                .path("lexicon");
        assertEquals("occupation", lexicon.path("domain").asText());
        assertTrue(lexicon.path("entries").size() >= 10);
        assertTrue(lexicon.toString().contains("Ogretmen"));
    }

    @Test
    void doesNotGiveEveryQuestionTheOccupationLexiconBecauseOfTheSurveyName() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TurkeyGeoDataService geoDataService = new TurkeyGeoDataService(objectMapper);
        var loadMethod = TurkeyGeoDataService.class.getDeclaredMethod("load");
        loadMethod.setAccessible(true);
        loadMethod.invoke(geoDataService);
        SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService =
                new SurveyQuestionAutoLexiconService(objectMapper, geoDataService);
        OperationAutoEntityLexiconService service =
                new OperationAutoEntityLexiconService(objectMapper, surveyQuestionAutoLexiconService, geoDataService);

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setName("Meslek ve yasam memnuniyeti");

        SurveyQuestion question = new SurveyQuestion();
        question.setId(UUID.randomUUID());
        question.setCode("district_problem");
        question.setTitle("Ilcenizin en onemli sorunu nedir?");
        question.setSettingsJson("{}");

        String payload = service.buildSourcePayloadJson(
                survey,
                "Mayis dalgasi",
                List.of(question),
                Map.of(question.getId(), List.of()),
                null
        );

        JsonNode lexicon = objectMapper.readTree(payload)
                .path("autoEntityLexiconPreview")
                .path("questionSuggestions")
                .get(0)
                .path("lexicon");
        assertTrue(!"occupation".equals(lexicon.path("domain").asText()));
    }

    @Test
    void combinesQuestionResearchAndSurveyPersonNamesForOpenEndedPoliticalQuestions() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TurkeyGeoDataService geoDataService = new TurkeyGeoDataService(objectMapper);
        var loadMethod = TurkeyGeoDataService.class.getDeclaredMethod("load");
        loadMethod.setAccessible(true);
        loadMethod.invoke(geoDataService);
        SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService =
                new SurveyQuestionAutoLexiconService(objectMapper, geoDataService);
        OperationAutoEntityLexiconService service =
                new OperationAutoEntityLexiconService(objectMapper, surveyQuestionAutoLexiconService, geoDataService);

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setName("Izmir belediye secimleri");

        SurveyQuestion openEndedQuestion = new SurveyQuestion();
        openEndedQuestion.setId(UUID.randomUUID());
        openEndedQuestion.setCode("favorite_politician");
        openEndedQuestion.setTitle("Izmir'deki siyasetciler denince akliniza ilk gelen, en begendiginiz siyasetcinin kim oldugunu soyler misiniz?");
        openEndedQuestion.setSettingsJson("{}");

        SurveyQuestion candidateQuestion = new SurveyQuestion();
        candidateQuestion.setId(UUID.randomUUID());
        candidateQuestion.setCode("known_candidate");
        candidateQuestion.setTitle("Bugun secim olsa hangi adaya oy verirsiniz?");
        candidateQuestion.setSettingsJson("{}");

        SurveyQuestionOption option = new SurveyQuestionOption();
        option.setActive(true);
        option.setOptionCode("cemil_tugay");
        option.setLabel("Cemil Tugay");
        option.setValue("Cemil Tugay");
        option.setSurveyQuestion(candidateQuestion);

        OperationAutoEntityLexiconService spyService =
                new OperationAutoEntityLexiconService(objectMapper, surveyQuestionAutoLexiconService, geoDataService) {
                    @Override
                    protected String fetchWikipediaWikitext(String title) {
                        if (title.toLowerCase().contains("belediye baskanlari")) {
                            return "|gorevdeki = [[Cemil Tugay]]";
                        }
                        if (title.toLowerCase().contains("milletvekilleri listesi")) {
                            return """
                                    '''[[TBMM 28. donem milletvekilleri listesi|TBMM 28. Donem]]''' || [[Murat Bakan]]
                                    ! colspan=4|
                                    """;
                        }
                        return null;
                    }
                };

        String payload = spyService.buildSourcePayloadJson(
                survey,
                "Izmir belediye secimleri",
                List.of(openEndedQuestion, candidateQuestion),
                Map.of(openEndedQuestion.getId(), List.of(), candidateQuestion.getId(), List.of(option)),
                null
        );

        JsonNode openEndedLexicon = objectMapper.readTree(payload)
                .path("autoEntityLexiconPreview")
                .path("questionSuggestions")
                .get(0)
                .path("lexicon");
        assertEquals("political", openEndedLexicon.path("domain").asText());
        assertEquals("question_research", openEndedLexicon.path("source").asText());
        assertTrue(openEndedLexicon.toString().contains("Cemil Tugay"));
        assertTrue(openEndedLexicon.toString().contains("Murat Bakan"));
    }

    @Test
    void excludesNonPersonPoliticalFallbackLabelsFromOpenEndedPersonQuestions() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TurkeyGeoDataService geoDataService = new TurkeyGeoDataService(objectMapper);
        var loadMethod = TurkeyGeoDataService.class.getDeclaredMethod("load");
        loadMethod.setAccessible(true);
        loadMethod.invoke(geoDataService);
        SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService =
                new SurveyQuestionAutoLexiconService(objectMapper, geoDataService);
        OperationAutoEntityLexiconService service =
                new OperationAutoEntityLexiconService(objectMapper, surveyQuestionAutoLexiconService, geoDataService);

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setName("Izmir belediye secimleri");

        SurveyQuestion openEndedQuestion = new SurveyQuestion();
        openEndedQuestion.setId(UUID.randomUUID());
        openEndedQuestion.setCode("favorite_politician");
        openEndedQuestion.setTitle("Izmir'deki siyasetciler denince akliniza ilk gelen, en begendiginiz siyasetcinin kim oldugunu soyler misiniz?");
        openEndedQuestion.setSettingsJson("{}");

        SurveyQuestion groupedPoliticalQuestion = new SurveyQuestion();
        groupedPoliticalQuestion.setId(UUID.randomUUID());
        groupedPoliticalQuestion.setCode("comparison");
        groupedPoliticalQuestion.setTitle("Karsilastirmayi neye gore yapiyorsunuz?");
        groupedPoliticalQuestion.setDescription("Genel secimlerde mi yoksa partiye gore mi degerlendiriyorsunuz?");
        groupedPoliticalQuestion.setSettingsJson("{}");

        SurveyQuestion candidateQuestion = new SurveyQuestion();
        candidateQuestion.setId(UUID.randomUUID());
        candidateQuestion.setCode("known_candidate");
        candidateQuestion.setTitle("Bugun secim olsa hangi adaya oy verirsiniz?");
        candidateQuestion.setSettingsJson("{}");

        SurveyQuestionOption groupedOption = new SurveyQuestionOption();
        groupedOption.setActive(true);
        groupedOption.setOptionCode("option_1");
        groupedOption.setLabel("Genel Secimlerde");
        groupedOption.setValue("Genel Secimlerde");
        groupedOption.setSurveyQuestion(groupedPoliticalQuestion);

        SurveyQuestionOption candidateOption = new SurveyQuestionOption();
        candidateOption.setActive(true);
        candidateOption.setOptionCode("cemil_tugay");
        candidateOption.setLabel("Cemil Tugay");
        candidateOption.setValue("Cemil Tugay");
        candidateOption.setSurveyQuestion(candidateQuestion);

        String payload = service.buildSourcePayloadJson(
                survey,
                "Izmir belediye secimleri",
                List.of(openEndedQuestion, groupedPoliticalQuestion, candidateQuestion),
                Map.of(
                        openEndedQuestion.getId(), List.of(),
                        groupedPoliticalQuestion.getId(), List.of(groupedOption),
                        candidateQuestion.getId(), List.of(candidateOption)
                ),
                null
        );

        JsonNode openEndedLexicon = objectMapper.readTree(payload)
                .path("autoEntityLexiconPreview")
                .path("questionSuggestions")
                .get(0)
                .path("lexicon");
        String serializedLexicon = openEndedLexicon.toString();

        assertTrue(serializedLexicon.contains("Cemil Tugay"));
        assertTrue(!serializedLexicon.contains("Genel Secimlerde"));
        assertTrue(!serializedLexicon.contains("option_1"));
    }

    @Test
    void researchesCityPoliticiansWhenSurveyHasNoInlineNames() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TurkeyGeoDataService geoDataService = new TurkeyGeoDataService(objectMapper);
        var loadMethod = TurkeyGeoDataService.class.getDeclaredMethod("load");
        loadMethod.setAccessible(true);
        loadMethod.invoke(geoDataService);
        SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService =
                new SurveyQuestionAutoLexiconService(objectMapper, geoDataService);
        OperationAutoEntityLexiconService service =
                new OperationAutoEntityLexiconService(objectMapper, surveyQuestionAutoLexiconService, geoDataService) {
                    @Override
                    protected String fetchWikipediaWikitext(String title) {
                        if (title.toLowerCase().contains("belediye baskanlari")) {
                            return "|gorevdeki = [[Cemil Tugay]]";
                        }
                        if (title.toLowerCase().contains("milletvekilleri listesi")) {
                            return """
                                    '''[[TBMM 28. donem milletvekilleri listesi|TBMM 28. Donem]]''' || [[Yuksel Taskin]]
                                    |-
                                    | [[Murat Bakan]]
                                    ! colspan=4|
                                    """;
                        }
                        return null;
                    }
                };

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setName("Izmir belediye secimleri");

        SurveyQuestion openEndedQuestion = new SurveyQuestion();
        openEndedQuestion.setId(UUID.randomUUID());
        openEndedQuestion.setCode("favorite_politician");
        openEndedQuestion.setTitle("Izmir'deki siyasetciler denince akliniza ilk gelen kim?");
        openEndedQuestion.setSettingsJson("{}");

        String payload = service.buildSourcePayloadJson(
                survey,
                "Izmir belediye secimleri",
                List.of(openEndedQuestion),
                Map.of(openEndedQuestion.getId(), List.of()),
                null
        );

        JsonNode openEndedLexicon = objectMapper.readTree(payload)
                .path("autoEntityLexiconPreview")
                .path("questionSuggestions")
                .get(0)
                .path("lexicon");
        assertEquals("question_research", openEndedLexicon.path("source").asText());
        assertTrue(openEndedLexicon.toString().contains("Cemil Tugay"));
        assertTrue(openEndedLexicon.toString().contains("Murat Bakan"));
    }
}
