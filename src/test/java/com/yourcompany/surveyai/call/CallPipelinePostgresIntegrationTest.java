package com.yourcompany.surveyai.call;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.yourcompany.surveyai.call.application.dto.request.InterviewAnswerRequest;
import com.yourcompany.surveyai.call.application.dto.request.InterviewConversationSignal;
import com.yourcompany.surveyai.call.application.service.CallInterviewOrchestrationService;
import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
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
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.domain.enums.OperationSourceType;
import com.yourcompany.surveyai.operation.domain.enums.OperationStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.domain.enums.SurveyStatus;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Runs the full application against a real PostgreSQL: all Flyway migrations, Hibernate schema
// validation, bean wiring, and the row locking the call queue relies on. Skipped when Docker is not available.
@SpringBootTest(properties = {
        "surveyai.seed.enabled=false",
        "surveyai.calling.queue.scheduler-enabled=false",
        "surveyai.calling.status-reconciliation.initial-delay-ms=3600000",
        "surveyai.calling.active-provider=MOCK",
        "surveyai.calling.mock.enabled=true",
        // Same start and end means calls are allowed all day, so the tests do not depend on the clock.
        "surveyai.calling.queue.calling-hours.start=00:00",
        "surveyai.calling.queue.calling-hours.end=00:00"
})
@Testcontainers(disabledWithoutDocker = true)
class CallPipelinePostgresIntegrationTest {

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
    private OperationRepository operationRepository;

    @Autowired
    private OperationContactRepository operationContactRepository;

    @Autowired
    private CallJobRepository callJobRepository;

    @Autowired
    private CallJobDispatcher callJobDispatcher;

    @Autowired
    private CallAttemptRepository callAttemptRepository;

    @Autowired
    private CallInterviewOrchestrationService callInterviewOrchestrationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    void migrationsAllowNumberQuestionsAndReadyOperations() {
        UUID surveyId = transactionTemplate.execute(status -> {
            Company company = createCompany();
            Survey survey = createSurvey(company);

            SurveyQuestion question = new SurveyQuestion();
            question.setCompany(company);
            question.setSurvey(survey);
            question.setCode("age");
            question.setQuestionOrder(1);
            question.setQuestionType(QuestionType.NUMBER);
            question.setTitle("Kaç yaşındasınız?");
            question.setRequired(true);
            question.setBranchConditionJson("{}");
            question.setSettingsJson("{}");
            surveyQuestionRepository.save(question);

            createOperation(company, survey, OperationStatus.READY);
            return survey.getId();
        });

        assertThat(count("SELECT COUNT(*) FROM survey_question WHERE survey_id = ? AND question_type = 'NUMBER'", surveyId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM operation WHERE survey_id = ? AND status = 'READY'", surveyId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '17' AND success",
                Long.class
        )).isEqualTo(1);
    }

    @Test
    void migrationsAllowLiveKitCallAttempts() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '18' AND success",
                Long.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_call_attempt_provider'",
                String.class
        )).contains("'LIVEKIT'").contains("'ELEVENLABS'");
    }

    @Test
    void lockForDispatchSkipsAnOperationThatAnotherTransactionHolds() throws Exception {
        UUID operationId = createRunningOperationWithJobs(1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holderThread = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> holder = holderThread.submit(() -> transactionTemplate.execute(status -> {
                boolean found = operationRepository.findAndLockForDispatch(operationId).isPresent();
                locked.countDown();
                awaitQuietly(release);
                return found;
            }));
            assertThat(locked.await(10, SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            Optional<Operation> secondLock = transactionTemplate.execute(status -> operationRepository.findAndLockForDispatch(operationId));
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);
            release.countDown();

            assertThat(holder.get(10, SECONDS)).isTrue();
            assertThat(secondLock).isEmpty();
            assertThat(waited).isLessThan(Duration.ofSeconds(3));
        } finally {
            release.countDown();
            holderThread.shutdownNow();
        }
    }

    @Test
    void concurrentDispatchPlacesOnlyOneCallForAnOperation() throws Exception {
        UUID operationId = createRunningOperationWithJobs(3);
        int dispatcherCount = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService dispatchers = Executors.newFixedThreadPool(dispatcherCount);
        int callsPlaced = 0;
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int index = 0; index < dispatcherCount; index++) {
                results.add(dispatchers.submit(() -> {
                    start.await();
                    return callJobDispatcher.dispatchAvailableJobs(operationId);
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                callsPlaced += result.get(30, SECONDS);
            }
        } finally {
            dispatchers.shutdownNow();
        }

        assertThat(callsPlaced).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM call_attempt WHERE operation_id = ?", operationId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM call_job WHERE operation_id = ? AND status = 'QUEUED'", operationId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM call_job WHERE operation_id = ? AND status = 'PENDING'", operationId)).isEqualTo(2);
    }

    @Test
    void concurrentToolCallsForOneCallAreSerializedAndTheRepeatedAnswerIsAppliedOnce() throws Exception {
        UUID callAttemptId = createLiveElevenLabsAttemptWithOpenQuestions();
        int requestCount = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService toolCalls = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                results.add(toolCalls.submit(() -> {
                    start.await();
                    return callInterviewOrchestrationService.submitAnswer(new InterviewAnswerRequest(
                            callAttemptId, null, null, "Fiyatlar uygun", InterviewConversationSignal.ANSWER));
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                // Without the row lock both requests create the survey response and one fails on its unique key.
                result.get(30, SECONDS);
            }
        } finally {
            toolCalls.shutdownNow();
        }

        assertThat(count("SELECT COUNT(*) FROM survey_response WHERE call_attempt_id = ?", callAttemptId)).isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM survey_answer a JOIN survey_response r ON r.id = a.survey_response_id WHERE r.call_attempt_id = ?",
                callAttemptId
        )).isEqualTo(1);
    }

    private UUID createLiveElevenLabsAttemptWithOpenQuestions() {
        return transactionTemplate.execute(status -> {
            Company company = createCompany();
            Survey survey = createSurvey(company);
            // Without an intro prompt the interview starts directly with the questions (no greeting or consent step).
            survey.setIntroPrompt(null);
            surveyRepository.save(survey);
            for (int order = 1; order <= 2; order++) {
                SurveyQuestion question = new SurveyQuestion();
                question.setCompany(company);
                question.setSurvey(survey);
                question.setCode("q" + order);
                question.setQuestionOrder(order);
                question.setQuestionType(QuestionType.OPEN_ENDED);
                question.setTitle(order == 1 ? "Neyi begendiniz?" : "Neyi gelistirelim?");
                question.setRequired(true);
                question.setBranchConditionJson("{}");
                question.setSettingsJson("{}");
                surveyQuestionRepository.save(question);
            }
            Operation operation = createOperation(company, survey, OperationStatus.RUNNING);

            OperationContact contact = new OperationContact();
            contact.setCompany(company);
            contact.setOperation(operation);
            contact.setPhoneNumber("905321234599");
            contact.setStatus(OperationContactStatus.CALLING);
            contact.setRetryCount(0);
            contact.setMetadataJson("{}");
            contact = operationContactRepository.save(contact);

            CallJob job = new CallJob();
            job.setCompany(company);
            job.setOperation(operation);
            job.setOperationContact(contact);
            job.setStatus(CallJobStatus.IN_PROGRESS);
            job.setPriority((short) 5);
            job.setScheduledFor(OffsetDateTime.now());
            job.setAvailableAt(OffsetDateTime.now());
            job.setAttemptCount(1);
            job.setMaxAttempts(3);
            job.setIdempotencyKey(operation.getId() + ":" + contact.getId());
            job = callJobRepository.save(job);

            CallAttempt attempt = new CallAttempt();
            attempt.setCompany(company);
            attempt.setCallJob(job);
            attempt.setOperation(operation);
            attempt.setOperationContact(contact);
            attempt.setAttemptNumber(1);
            attempt.setProvider(CallProvider.ELEVENLABS);
            attempt.setProviderCallId("conv-" + UUID.randomUUID());
            attempt.setStatus(CallAttemptStatus.IN_PROGRESS);
            attempt.setDialedAt(OffsetDateTime.now());
            attempt.setConnectedAt(OffsetDateTime.now());
            attempt.setRawProviderPayload("{}");
            return callAttemptRepository.save(attempt).getId();
        });
    }

    private UUID createRunningOperationWithJobs(int jobCount) {
        return transactionTemplate.execute(status -> {
            Company company = createCompany();
            Survey survey = createSurvey(company);
            Operation operation = createOperation(company, survey, OperationStatus.RUNNING);
            OffsetDateTime availableAt = OffsetDateTime.now().minusMinutes(1);

            for (int index = 0; index < jobCount; index++) {
                OperationContact contact = new OperationContact();
                contact.setCompany(company);
                contact.setOperation(operation);
                contact.setFirstName("Katilimci " + index);
                contact.setPhoneNumber("90532123450" + index);
                contact.setStatus(OperationContactStatus.PENDING);
                contact.setRetryCount(0);
                contact.setMetadataJson("{}");
                contact = operationContactRepository.save(contact);

                CallJob job = new CallJob();
                job.setCompany(company);
                job.setOperation(operation);
                job.setOperationContact(contact);
                job.setStatus(CallJobStatus.PENDING);
                job.setPriority((short) 5);
                job.setScheduledFor(availableAt);
                job.setAvailableAt(availableAt);
                job.setAttemptCount(0);
                job.setMaxAttempts(3);
                job.setIdempotencyKey(operation.getId() + ":" + contact.getId());
                callJobRepository.save(job);
            }
            return operation.getId();
        });
    }

    private Company createCompany() {
        Company company = new Company();
        company.setName("Queue Test Research");
        company.setSlug("queue-test-" + UUID.randomUUID());
        company.setStatus(CompanyStatus.ACTIVE);
        company.setTimezone("Europe/Istanbul");
        company.setMetadataJson(new LinkedHashMap<>());
        return companyRepository.save(company);
    }

    private Survey createSurvey(Company company) {
        Survey survey = new Survey();
        survey.setCompany(company);
        survey.setName("Kuyruk testi");
        survey.setStatus(SurveyStatus.PUBLISHED);
        survey.setLanguageCode("tr");
        survey.setIntroPrompt("Merhaba");
        survey.setClosingPrompt("Tesekkurler");
        survey.setMaxRetryPerQuestion(2);
        return surveyRepository.save(survey);
    }

    private Operation createOperation(Company company, Survey survey, OperationStatus status) {
        Operation operation = new Operation();
        operation.setCompany(company);
        operation.setSurvey(survey);
        operation.setName("Kuyruk testi operasyonu");
        operation.setStatus(status);
        operation.setSourceType(OperationSourceType.STANDARD);
        operation.setSourcePayloadJson("{}");
        return operationRepository.save(operation);
    }

    private long count(String sql, UUID id) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, id);
        return value == null ? 0 : value;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(15, SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
