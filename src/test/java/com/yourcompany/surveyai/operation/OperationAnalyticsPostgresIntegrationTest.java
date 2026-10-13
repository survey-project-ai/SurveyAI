package com.yourcompany.surveyai.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.domain.enums.CompanyStatus;
import com.yourcompany.surveyai.common.repository.CompanyRepository;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsQuestionSummaryDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsResponseDto;
import com.yourcompany.surveyai.operation.application.service.OperationService;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.domain.enums.OperationSourceType;
import com.yourcompany.surveyai.operation.domain.enums.OperationStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.response.domain.entity.SurveyAnswer;
import com.yourcompany.surveyai.response.domain.entity.SurveyResponse;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import com.yourcompany.surveyai.response.repository.SurveyAnswerRepository;
import com.yourcompany.surveyai.response.repository.SurveyResponseRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.domain.enums.SurveyStatus;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionOptionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Runs operation analytics against a real PostgreSQL: the JPQL projection (including the jsonb consent
// lookup), one response per contact, and a query count that does not grow with the number of respondents.
@SpringBootTest(properties = {
        "surveyai.seed.enabled=false",
        "surveyai.calling.queue.scheduler-enabled=false",
        "surveyai.calling.status-reconciliation.initial-delay-ms=3600000",
        "surveyai.calling.active-provider=MOCK",
        "surveyai.calling.mock.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Testcontainers(disabledWithoutDocker = true)
class OperationAnalyticsPostgresIntegrationTest {

    private static final int RESPONDENT_COUNT = 6;
    private static final String LONG_TRANSCRIPT = "agent: Merhaba. user: Alo. ".repeat(500);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private SurveyRepository surveyRepository;

    @Autowired
    private SurveyQuestionRepository surveyQuestionRepository;

    @Autowired
    private SurveyQuestionOptionRepository surveyQuestionOptionRepository;

    @Autowired
    private OperationRepository operationRepository;

    @Autowired
    private OperationContactRepository operationContactRepository;

    @Autowired
    private CallJobRepository callJobRepository;

    @Autowired
    private CallAttemptRepository callAttemptRepository;

    @Autowired
    private SurveyResponseRepository surveyResponseRepository;

    @Autowired
    private SurveyAnswerRepository surveyAnswerRepository;

    @Autowired
    private OperationService operationService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void analyticsCountsEachContactOnceWithAFixedNumberOfQueries() {
        Fixture fixture = new TransactionTemplate(transactionManager).execute(status -> createOperationWithResponses());
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        OperationAnalyticsResponseDto analytics = operationService.getOperationAnalytics(fixture.companyId(), fixture.operationId());

        long statementCount = statistics.getPrepareStatementCount();
        assertThat(analytics.totalResponses()).isEqualTo(RESPONDENT_COUNT + 1);
        assertThat(analytics.respondedContacts()).isEqualTo(RESPONDENT_COUNT);
        assertThat(analytics.completedResponses()).isEqualTo(RESPONDENT_COUNT);
        assertThat(analytics.partialResponses()).isZero();
        assertThat(analytics.consentBreakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .contains("Katılmayı kabul etti:" + RESPONDENT_COUNT);

        OperationAnalyticsQuestionSummaryDto choiceSummary = analytics.questionSummaries().get(0);
        assertThat(choiceSummary.answeredCount()).isEqualTo(RESPONDENT_COUNT);
        assertThat(choiceSummary.breakdown())
                .extracting(item -> item.label() + ":" + item.count())
                .contains("Evet:" + RESPONDENT_COUNT);

        OperationAnalyticsQuestionSummaryDto openSummary = analytics.questionSummaries().get(1);
        assertThat(openSummary.rawResponses()).hasSize(RESPONDENT_COUNT);
        assertThat(openSummary.rawResponses())
                .allSatisfy(sample -> {
                    assertThat(sample.callJobId()).isNotNull();
                    assertThat(sample.respondentName()).startsWith("Katilimci ");
                });
        // Operation, contact count, job counts, response rows, questions, options and answers. The old code
        // also loaded every attempt, job and contact behind the open-ended samples, one by one.
        assertThat(statementCount).isLessThanOrEqualTo(10);
    }

    private Fixture createOperationWithResponses() {
        Company company = new Company();
        company.setName("Analytics Test Research");
        company.setSlug("analytics-test-" + UUID.randomUUID());
        company.setStatus(CompanyStatus.ACTIVE);
        company.setTimezone("Europe/Istanbul");
        company.setMetadataJson(new LinkedHashMap<>());
        company = companyRepository.save(company);

        Survey survey = new Survey();
        survey.setCompany(company);
        survey.setName("Analitik testi");
        survey.setStatus(SurveyStatus.PUBLISHED);
        survey.setLanguageCode("tr");
        survey.setMaxRetryPerQuestion(2);
        survey = surveyRepository.save(survey);

        SurveyQuestion choiceQuestion = saveQuestion(company, survey, "satisfied", 1, QuestionType.SINGLE_CHOICE, "Memnun musunuz?");
        SurveyQuestion openQuestion = saveQuestion(company, survey, "comment", 2, QuestionType.OPEN_ENDED, "Yorumunuz nedir?");
        SurveyQuestionOption yesOption = saveOption(company, choiceQuestion, 1, "yes", "Evet");
        SurveyQuestionOption noOption = saveOption(company, choiceQuestion, 2, "no", "Hayir");

        Operation operation = new Operation();
        operation.setCompany(company);
        operation.setSurvey(survey);
        operation.setName("Analitik testi operasyonu");
        operation.setStatus(OperationStatus.RUNNING);
        operation.setSourceType(OperationSourceType.STANDARD);
        operation.setSourcePayloadJson("{}");
        operation = operationRepository.save(operation);

        for (int index = 0; index < RESPONDENT_COUNT; index++) {
            OperationContact contact = new OperationContact();
            contact.setCompany(company);
            contact.setOperation(operation);
            contact.setFirstName("Katilimci " + index);
            contact.setPhoneNumber("90532123460" + index);
            contact.setStatus(OperationContactStatus.COMPLETED);
            contact.setRetryCount(0);
            contact.setMetadataJson("{}");
            contact = operationContactRepository.save(contact);

            CallJob job = new CallJob();
            job.setCompany(company);
            job.setOperation(operation);
            job.setOperationContact(contact);
            job.setStatus(CallJobStatus.COMPLETED);
            job.setPriority((short) 5);
            job.setScheduledFor(OffsetDateTime.now());
            job.setAvailableAt(OffsetDateTime.now());
            job.setAttemptCount(index == 0 ? 2 : 1);
            job.setMaxAttempts(3);
            job.setIdempotencyKey(operation.getId() + ":" + contact.getId());
            job = callJobRepository.save(job);

            if (index == 0) {
                // The first contact was cut off once and completed the survey on the redial.
                SurveyResponse cutOff = saveResponse(company, operation, contact, saveAttempt(company, operation, contact, job, 1),
                        SurveyResponseStatus.PARTIAL, 50, OffsetDateTime.now().minusDays(1));
                saveChoiceAnswer(cutOff, choiceQuestion, noOption);
            }
            SurveyResponse completed = saveResponse(company, operation, contact, saveAttempt(company, operation, contact, job, index == 0 ? 2 : 1),
                    SurveyResponseStatus.COMPLETED, 100, OffsetDateTime.now().minusHours(1));
            saveChoiceAnswer(completed, choiceQuestion, yesOption);
            saveOpenAnswer(completed, openQuestion, "Hizmet cok iyiydi " + index);
        }
        return new Fixture(company.getId(), operation.getId());
    }

    private SurveyQuestion saveQuestion(Company company, Survey survey, String code, int order, QuestionType type, String title) {
        SurveyQuestion question = new SurveyQuestion();
        question.setCompany(company);
        question.setSurvey(survey);
        question.setCode(code);
        question.setQuestionOrder(order);
        question.setQuestionType(type);
        question.setTitle(title);
        question.setRequired(true);
        question.setBranchConditionJson("{}");
        question.setSettingsJson("{}");
        return surveyQuestionRepository.save(question);
    }

    private SurveyQuestionOption saveOption(Company company, SurveyQuestion question, int order, String code, String label) {
        SurveyQuestionOption option = new SurveyQuestionOption();
        option.setCompany(company);
        option.setSurveyQuestion(question);
        option.setOptionOrder(order);
        option.setOptionCode(code);
        option.setLabel(label);
        option.setValue(code);
        option.setActive(true);
        return surveyQuestionOptionRepository.save(option);
    }

    private CallAttempt saveAttempt(Company company, Operation operation, OperationContact contact, CallJob job, int attemptNumber) {
        CallAttempt attempt = new CallAttempt();
        attempt.setCompany(company);
        attempt.setCallJob(job);
        attempt.setOperation(operation);
        attempt.setOperationContact(contact);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setProvider(CallProvider.ELEVENLABS);
        attempt.setProviderCallId("conv-" + UUID.randomUUID());
        attempt.setStatus(CallAttemptStatus.COMPLETED);
        attempt.setRawProviderPayload("{}");
        return callAttemptRepository.save(attempt);
    }

    private SurveyResponse saveResponse(
            Company company,
            Operation operation,
            OperationContact contact,
            CallAttempt attempt,
            SurveyResponseStatus status,
            int completionPercent,
            OffsetDateTime completedAt
    ) {
        SurveyResponse response = new SurveyResponse();
        response.setCompany(company);
        response.setSurvey(operation.getSurvey());
        response.setOperation(operation);
        response.setOperationContact(contact);
        response.setCallAttempt(attempt);
        response.setStatus(status);
        response.setRespondentPhone(contact.getPhoneNumber());
        response.setCompletionPercent(BigDecimal.valueOf(completionPercent));
        response.setStartedAt(completedAt.minusMinutes(5));
        response.setCompletedAt(completedAt);
        response.setTranscriptText(LONG_TRANSCRIPT);
        response.setTranscriptJson("{\"openingConsentState\":\"GRANTED\",\"transcript\":\"" + LONG_TRANSCRIPT + "\"}");
        return surveyResponseRepository.save(response);
    }

    private void saveChoiceAnswer(SurveyResponse response, SurveyQuestion question, SurveyQuestionOption option) {
        SurveyAnswer answer = newAnswer(response, question, QuestionType.SINGLE_CHOICE);
        answer.setSelectedOption(option);
        answer.setAnswerText(option.getLabel());
        surveyAnswerRepository.save(answer);
    }

    private void saveOpenAnswer(SurveyResponse response, SurveyQuestion question, String text) {
        SurveyAnswer answer = newAnswer(response, question, QuestionType.OPEN_ENDED);
        answer.setAnswerText(text);
        answer.setRawInputText(text);
        surveyAnswerRepository.save(answer);
    }

    private SurveyAnswer newAnswer(SurveyResponse response, SurveyQuestion question, QuestionType type) {
        SurveyAnswer answer = new SurveyAnswer();
        answer.setCompany(response.getCompany());
        answer.setSurveyResponse(response);
        answer.setSurveyQuestion(question);
        answer.setAnswerType(type);
        answer.setAnswerJson("{}");
        answer.setValid(true);
        answer.setRetryCount(0);
        return answer;
    }

    private record Fixture(UUID companyId, UUID operationId) {
    }
}
