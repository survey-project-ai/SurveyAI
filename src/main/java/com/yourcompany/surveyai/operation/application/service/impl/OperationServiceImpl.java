package com.yourcompany.surveyai.operation.application.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.auth.application.RequestAuthContext;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.operation.application.dto.request.CreateOperationRequest;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsBreakdownItemDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsAudienceBreakdownDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsInsightItemDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsQuestionGroupRowDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsQuestionGroupSeriesDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsQuestionGroupSummaryDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsSampleResponseDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsQuestionSummaryDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsResponseDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationAnalyticsTrendPointDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationExecutionSummaryDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationReadinessDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationResponseDto;
import com.yourcompany.surveyai.operation.application.service.OperationService;
import com.yourcompany.surveyai.operation.application.support.OperationAutoEntityLexiconService;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationSourceType;
import com.yourcompany.surveyai.operation.domain.enums.OperationStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.common.domain.entity.AppUser;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.common.repository.AppUserRepository;
import com.yourcompany.surveyai.common.repository.CompanyRepository;
import com.yourcompany.surveyai.response.domain.entity.SurveyAnswer;
import com.yourcompany.surveyai.response.domain.entity.SurveyResponse;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import com.yourcompany.surveyai.response.repository.SurveyAnswerRepository;
import com.yourcompany.surveyai.response.repository.SurveyResponseAnalyticsRow;
import com.yourcompany.surveyai.response.repository.SurveyResponseRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.domain.enums.SurveyStatus;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionOptionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OperationServiceImpl implements OperationService {

    private static final Logger log = LoggerFactory.getLogger(OperationServiceImpl.class);

    private static final Set<OperationStatus> STARTABLE_STATUSES = EnumSet.of(
            OperationStatus.DRAFT,
            OperationStatus.READY,
            OperationStatus.SCHEDULED
    );

    private static final Set<CallJobStatus> OPEN_CALL_JOB_STATUSES = EnumSet.of(
            CallJobStatus.PENDING,
            CallJobStatus.QUEUED,
            CallJobStatus.IN_PROGRESS,
            CallJobStatus.RETRY
    );

    private static final Set<CallJobStatus> LIVE_CALL_JOB_STATUSES = EnumSet.of(
            CallJobStatus.QUEUED,
            CallJobStatus.IN_PROGRESS
    );

    private static final Duration LIVE_CALL_MAX_AGE = Duration.ofHours(1);

    private static final Set<String> OPEN_ENDED_STOP_WORDS = Set.of(
            "ve", "veya", "ile", "icin", "ama", "fakat", "gibi", "cok", "daha", "bir", "iki", "uc",
            "bu", "su", "o", "ben", "bence", "biz", "siz", "hem", "de", "da", "ki", "mi", "mu",
            "midir", "nedir", "olan", "olarak", "yani", "artik", "gerek", "gerekiyor", "olmali",
            "olsun", "var", "yok", "olanlar", "olmasi", "oluyor", "bile", "hala", "once", "sonra",
            "sanki", "biraz", "fazla", "az", "en", "çok", "dair", "the", "and", "for",
            "with", "that", "this", "are", "is", "was", "were", "have", "has", "had"
    );

    private static final Set<String> OPEN_ENDED_CONSENT_ARTIFACTS = Set.of(
            "evet",
            "hayir",
            "olur",
            "olabilir",
            "tamam",
            "tabii",
            "tabi",
            "uygunum",
            "uygun degil",
            "musait degilim",
            "istemiyorum",
            "hayir istemiyorum",
            "simdi olmaz",
            "rahatsiz etmeyin",
            "aramayin",
            "yes",
            "no",
            "nope",
            "not now",
            "go ahead"
    );

    private static final String CONSENT_GRANTED = "GRANTED";
    private static final String CONSENT_DECLINED = "DECLINED";
    private static final String CONSENT_UNCLEAR = "UNCLEAR";

    private static final Map<String, String> OPEN_ENDED_THEME_BY_KEYWORD = Map.ofEntries(
            Map.entry("ulasim", "Ulasim"),
            Map.entry("trafik", "Ulasim"),
            Map.entry("yol", "Ulasim"),
            Map.entry("otobus", "Ulasim"),
            Map.entry("metro", "Ulasim"),
            Map.entry("ekonomi", "Ekonomi"),
            Map.entry("issizlik", "Ekonomi"),
            Map.entry("is", "Ekonomi"),
            Map.entry("istihdam", "Ekonomi"),
            Map.entry("maas", "Ekonomi"),
            Map.entry("enflasyon", "Ekonomi"),
            Map.entry("pahalilik", "Ekonomi"),
            Map.entry("fiyat", "Fiyat"),
            Map.entry("ucret", "Fiyat"),
            Map.entry("maliyet", "Fiyat"),
            Map.entry("egitim", "Egitim"),
            Map.entry("okul", "Egitim"),
            Map.entry("ogrenci", "Egitim"),
            Map.entry("universite", "Egitim"),
            Map.entry("saglik", "Saglik"),
            Map.entry("hastane", "Saglik"),
            Map.entry("doktor", "Saglik"),
            Map.entry("randevu", "Saglik"),
            Map.entry("barinma", "Barinma"),
            Map.entry("konut", "Barinma"),
            Map.entry("kira", "Barinma"),
            Map.entry("ev", "Barinma"),
            Map.entry("deprem", "Afet"),
            Map.entry("afet", "Afet"),
            Map.entry("sel", "Afet"),
            Map.entry("yangin", "Afet"),
            Map.entry("guvenlik", "Guvenlik"),
            Map.entry("asayis", "Guvenlik"),
            Map.entry("suc", "Guvenlik"),
            Map.entry("temizlik", "Temizlik"),
            Map.entry("cop", "Temizlik"),
            Map.entry("cevre", "Cevre"),
            Map.entry("yesil", "Cevre"),
            Map.entry("park", "Cevre"),
            Map.entry("altyapi", "Altyapi"),
            Map.entry("su", "Altyapi"),
            Map.entry("kanalizasyon", "Altyapi"),
            Map.entry("internet", "Altyapi"),
            Map.entry("destek", "Destek"),
            Map.entry("yardim", "Destek"),
            Map.entry("hizmet", "Destek"),
            Map.entry("kalite", "Kalite"),
            Map.entry("performans", "Performans"),
            Map.entry("hiz", "Performans"),
            Map.entry("yavas", "Performans"),
            Map.entry("uygulama", "Uygulama"),
            Map.entry("mobil", "Uygulama"),
            Map.entry("web", "Uygulama"),
            Map.entry("sosyal", "Sosyal alanlar"),
            Map.entry("kultur", "Sosyal alanlar"),
            Map.entry("spor", "Sosyal alanlar")
    );

    private final OperationRepository operationRepository;
    private final OperationContactRepository operationContactRepository;
    private final CallAttemptRepository callAttemptRepository;
    private final CallJobRepository callJobRepository;
    private final CompanyRepository companyRepository;
    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository surveyQuestionRepository;
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository;
    private final SurveyResponseRepository surveyResponseRepository;
    private final SurveyAnswerRepository surveyAnswerRepository;
    private final AppUserRepository appUserRepository;
    private final CallJobDispatcher callJobDispatcher;
    private final RequestAuthContext requestAuthContext;
    private final Validator validator;
    private final ObjectMapper objectMapper;
    private final OperationAutoEntityLexiconService operationAutoEntityLexiconService;

    public OperationServiceImpl(
            OperationRepository operationRepository,
            OperationContactRepository operationContactRepository,
            CallAttemptRepository callAttemptRepository,
            CallJobRepository callJobRepository,
            CompanyRepository companyRepository,
            SurveyRepository surveyRepository,
            SurveyQuestionRepository surveyQuestionRepository,
            SurveyQuestionOptionRepository surveyQuestionOptionRepository,
            SurveyResponseRepository surveyResponseRepository,
            SurveyAnswerRepository surveyAnswerRepository,
            AppUserRepository appUserRepository,
            CallJobDispatcher callJobDispatcher,
            RequestAuthContext requestAuthContext,
            Validator validator,
            ObjectMapper objectMapper,
            OperationAutoEntityLexiconService operationAutoEntityLexiconService
    ) {
        this.operationRepository = operationRepository;
        this.operationContactRepository = operationContactRepository;
        this.callAttemptRepository = callAttemptRepository;
        this.callJobRepository = callJobRepository;
        this.companyRepository = companyRepository;
        this.surveyRepository = surveyRepository;
        this.surveyQuestionRepository = surveyQuestionRepository;
        this.surveyQuestionOptionRepository = surveyQuestionOptionRepository;
        this.surveyResponseRepository = surveyResponseRepository;
        this.surveyAnswerRepository = surveyAnswerRepository;
        this.appUserRepository = appUserRepository;
        this.callJobDispatcher = callJobDispatcher;
        this.requestAuthContext = requestAuthContext;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.operationAutoEntityLexiconService = operationAutoEntityLexiconService;
    }

    @Override
    @Transactional
    public OperationResponseDto createOperation(UUID companyId, CreateOperationRequest request) {
        validateRequest(request);

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));

        Survey survey = surveyRepository.findByIdAndCompany_IdAndDeletedAtIsNull(request.getSurveyId(), companyId)
                .orElseThrow(() -> new NotFoundException("Survey not found for company: " + request.getSurveyId()));

        if (survey.getStatus() != SurveyStatus.PUBLISHED) {
            throw new ValidationException("Operation can only be created from a published survey");
        }

        AppUser createdBy = resolveUserForCompany(companyId, request.getCreatedByUserId());

        Operation operation = new Operation();
        operation.setCompany(company);
        operation.setSurvey(survey);
        operation.setName(request.getName().trim());
        operation.setStatus(OperationStatus.DRAFT);
        operation.setSourceType(OperationSourceType.STANDARD);
        operation.setSourcePayloadJson(buildOperationSourcePayload(survey, request.getName().trim()));
        operation.setScheduledAt(request.getScheduledAt());
        operation.setCreatedBy(createdBy);

        Operation savedOperation = operationRepository.save(operation);
        return toDto(savedOperation, 0L, List.of());
    }

    @Override
    public OperationResponseDto getOperationById(UUID companyId, UUID operationId) {
        Operation operation = getOperation(companyId, operationId);
        long contactCount = countContacts(operation);
        syncLifecycleState(operation, contactCount);

        return toDto(operation, contactCount, List.of());
    }

    @Override
    public List<OperationResponseDto> listOperationsByCompany(UUID companyId) {
        ensureCompanyExists(companyId);

        return operationRepository.findAllByCompany_IdAndDeletedAtIsNull(companyId).stream()
                .sorted(Comparator.comparing(Operation::getCreatedAt).reversed())
                .map(operation -> {
                    long contactCount = countContacts(operation);
                    syncLifecycleState(operation, contactCount);
                    return toDto(operation, contactCount, List.of());
                })
                .toList();
    }

    @Override
    @Transactional
    public OperationResponseDto startOperation(UUID companyId, UUID operationId) {
        Operation operation = getOperation(companyId, operationId);
        long contactCount = countContacts(operation);
        syncLifecycleState(operation, contactCount);

        OperationReadinessDto readiness = buildReadiness(operation, contactCount);
        if (!readiness.readyToStart()) {
            throw new ValidationException(String.join(" ", readiness.blockingReasons()));
        }

        List<OperationContact> contacts = operationContactRepository
                .findAllByOperation_IdAndCompany_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId, companyId);
        List<CallJob> existingCallJobs = callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId);
        Set<UUID> preparedContactIds = existingCallJobs.stream()
                .map(callJob -> callJob.getOperationContact().getId())
                .collect(java.util.stream.Collectors.toSet());

        OffsetDateTime startedAt = OffsetDateTime.now();
        List<CallJob> newJobs = new ArrayList<>();
        for (OperationContact contact : contacts) {
            if (preparedContactIds.contains(contact.getId())) {
                continue;
            }

            CallJob job = new CallJob();
            job.setCompany(operation.getCompany());
            job.setOperation(operation);
            job.setOperationContact(contact);
            job.setStatus(CallJobStatus.PENDING);
            job.setPriority((short) 5);
            job.setScheduledFor(startedAt);
            job.setAvailableAt(startedAt);
            job.setAttemptCount(0);
            job.setMaxAttempts(3);
            job.setIdempotencyKey(operation.getId() + ":" + contact.getId());
            newJobs.add(job);
        }

        // Starting fails fast when no call could be placed; the calls themselves run after commit.
        callJobDispatcher.validateActiveProviderConfiguration();
        operation.setStartedAt(startedAt);
        operation.setStatus(OperationStatus.RUNNING);
        Operation savedOperation = operationRepository.save(operation);

        if (!newJobs.isEmpty()) {
            callJobRepository.saveAll(newJobs);
        }
        callJobDispatcher.requestDispatch(operationId);

        return toDto(savedOperation, contactCount, newJobs);
    }

    @Override
    @Transactional
    public OperationResponseDto pauseOperation(UUID companyId, UUID operationId) {
        Operation operation = getOperation(companyId, operationId);
        long contactCount = countContacts(operation);

        if (operation.getStatus() != OperationStatus.RUNNING) {
            throw new ValidationException("Yalnizca yurutulen operasyon duraklatilabilir.");
        }

        operation.setStatus(OperationStatus.PAUSED);
        Operation savedOperation = operationRepository.save(operation);
        return toDto(savedOperation, contactCount, List.of());
    }

    @Override
    @Transactional
    public OperationResponseDto resumeOperation(UUID companyId, UUID operationId) {
        Operation operation = getOperation(companyId, operationId);
        long contactCount = countContacts(operation);

        if (operation.getStatus() != OperationStatus.PAUSED) {
            throw new ValidationException("Yalnizca duraklatilmis operasyon devam ettirilebilir.");
        }

        callJobDispatcher.validateActiveProviderConfiguration();
        operation.setStatus(OperationStatus.RUNNING);
        Operation savedOperation = operationRepository.save(operation);
        callJobDispatcher.requestDispatch(operationId);
        return toDto(savedOperation, contactCount, List.of());
    }

    @Override
    @Transactional
    public void deleteOperation(UUID companyId, UUID operationId) {
        Operation operation = getOperation(companyId, operationId);

        boolean hasOpenCallJobs = callJobRepository.existsByOperation_IdAndStatusInAndDeletedAtIsNull(
                operationId,
                OPEN_CALL_JOB_STATUSES
        );
        if (operation.getStatus() == OperationStatus.RUNNING && hasOpenCallJobs) {
            throw new ValidationException("Yurutulen operasyon silinemez. Once duraklatin.");
        }
        // Pausing stops new dispatches but not a call that is already placed. Deleting now would
        // soft-delete the rows that call's tool requests and post-call webhook still need. Jobs that
        // have not changed for a long time are treated as stuck so they cannot block deletion forever.
        if (callJobRepository.existsByOperation_IdAndStatusInAndUpdatedAtAfterAndDeletedAtIsNull(
                operationId,
                LIVE_CALL_JOB_STATUSES,
                OffsetDateTime.now().minus(LIVE_CALL_MAX_AGE)
        )) {
            throw new ValidationException("Devam eden bir arama var. Arama bittikten sonra operasyonu silebilirsiniz.");
        }

        OffsetDateTime deletedAt = OffsetDateTime.now();

        List<CallJob> callJobs = callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId);
        for (CallJob callJob : callJobs) {
            List<CallAttempt> attempts = callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(callJob.getId());
            for (CallAttempt attempt : attempts) {
                if (attempt.getDeletedAt() == null) {
                    attempt.setDeletedAt(deletedAt);
                    callAttemptRepository.save(attempt);
                }
            }
            callJob.setDeletedAt(deletedAt);
            callJobRepository.save(callJob);
        }

        List<SurveyResponse> responses = surveyResponseRepository.findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId);
        List<UUID> responseIds = responses.stream().map(SurveyResponse::getId).toList();
        if (!responseIds.isEmpty()) {
            for (SurveyAnswer answer : surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(responseIds)) {
                answer.setDeletedAt(deletedAt);
                surveyAnswerRepository.save(answer);
            }
        }
        for (SurveyResponse response : responses) {
            response.setDeletedAt(deletedAt);
            surveyResponseRepository.save(response);
        }

        for (OperationContact contact : operationContactRepository.findAllByOperation_IdAndDeletedAtIsNull(operationId)) {
            contact.setDeletedAt(deletedAt);
            operationContactRepository.save(contact);
        }

        operation.setDeletedAt(deletedAt);
        operationRepository.save(operation);
    }

    @Override
    public OperationAnalyticsResponseDto getOperationAnalytics(UUID companyId, UUID operationId) {
        Operation operation = getOperation(companyId, operationId);
        long totalContacts = countContacts(operation);
        syncLifecycleState(operation, totalContacts);

        Map<CallJobStatus, Long> jobCountsByStatus = callJobRepository
                .countGroupedByStatusForOperation(operationId)
                .stream()
                .collect(Collectors.toMap(row -> (CallJobStatus) row[0], row -> (Long) row[1]));
        long totalJobs = jobCountsByStatus.values().stream().mapToLong(Long::longValue).sum();
        long queuedJobs = jobCountsByStatus.getOrDefault(CallJobStatus.PENDING, 0L)
                + jobCountsByStatus.getOrDefault(CallJobStatus.QUEUED, 0L)
                + jobCountsByStatus.getOrDefault(CallJobStatus.RETRY, 0L);
        long inProgressJobs = jobCountsByStatus.getOrDefault(CallJobStatus.IN_PROGRESS, 0L);
        long completedCallJobs = jobCountsByStatus.getOrDefault(CallJobStatus.COMPLETED, 0L);
        long failedCallJobs = jobCountsByStatus.getOrDefault(CallJobStatus.FAILED, 0L)
                + jobCountsByStatus.getOrDefault(CallJobStatus.DEAD_LETTER, 0L);
        long skippedCallJobs = jobCountsByStatus.getOrDefault(CallJobStatus.CANCELLED, 0L);
        long totalCallsAttempted = totalJobs - queuedJobs;

        List<SurveyResponseAnalyticsRow> allResponses = surveyResponseRepository.findAnalyticsRowsByOperationId(operationId);
        List<SurveyResponseAnalyticsRow> responses = selectOneResponsePerContact(allResponses);
        List<SurveyQuestion> questions = surveyQuestionRepository
                .findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(operation.getSurvey().getId());
        List<UUID> questionIds = questions.stream().map(SurveyQuestion::getId).toList();
        Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId = questionIds.isEmpty()
                ? Map.of()
                : surveyQuestionOptionRepository
                        .findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(questionIds)
                        .stream()
                        .collect(Collectors.groupingBy(
                                option -> option.getSurveyQuestion().getId(),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));

        Map<UUID, SurveyResponseAnalyticsRow> responsesById = responses.stream()
                .collect(Collectors.toMap(SurveyResponseAnalyticsRow::id, response -> response));
        // Answers of the responses that do not count for their contact are dropped by the responsesById filters below.
        List<SurveyAnswer> answers = responses.isEmpty()
                ? List.of()
                : surveyAnswerRepository.findAllForOperationAnalytics(operationId);

        Map<UUID, List<SurveyAnswer>> answersByQuestionId = answers.stream()
                .filter(answer -> responsesById.containsKey(answer.getSurveyResponse().getId()))
                .collect(Collectors.groupingBy(
                        answer -> answer.getSurveyQuestion().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Set<UUID> usableResponseIds = answers.stream()
                .filter(answer -> responsesById.containsKey(answer.getSurveyResponse().getId()))
                .filter(this::hasUsableAnalyticsAnswer)
                .map(answer -> answer.getSurveyResponse().getId())
                .collect(Collectors.toSet());
        Set<UUID> respondedContactIds = usableResponseIds.stream()
                .map(responsesById::get)
                .filter(Objects::nonNull)
                .map(SurveyResponseAnalyticsRow::operationContactId)
                .collect(Collectors.toSet());
        long respondedContacts = respondedContactIds.size();

        long completedResponses = countDistinctResponseContactsByStatus(responses, usableResponseIds, SurveyResponseStatus.COMPLETED);
        long partialResponses = countDistinctResponseContactsByStatus(responses, usableResponseIds, SurveyResponseStatus.PARTIAL);
        long abandonedResponses = countDistinctResponseContactsByStatus(responses, usableResponseIds, SurveyResponseStatus.ABANDONED);
        long invalidResponses = countDistinctResponseContactsByStatus(responses, usableResponseIds, SurveyResponseStatus.INVALID);

        double completionRate = percentage(completedResponses, respondedContacts);
        double responseRate = percentage(respondedContacts, totalContacts);
        double personResponseRate = percentage(respondedContacts, totalContacts);
        double contactReachRate = percentage(totalCallsAttempted, totalContacts);
        double participationRate = percentage(respondedContacts, totalContacts);
        double averageCompletionPercent = round(
                responses.stream()
                        .filter(response -> usableResponseIds.contains(response.id()))
                        .map(SurveyResponseAnalyticsRow::completionPercent)
                        .filter(Objects::nonNull)
                        .mapToDouble(BigDecimal::doubleValue)
                        .average()
                        .orElse(0)
        );

        List<OperationAnalyticsBreakdownItemDto> outcomeBreakdown = List.of(
                new OperationAnalyticsBreakdownItemDto("queued", "Kuyrukta", queuedJobs, percentage(queuedJobs, totalJobs)),
                new OperationAnalyticsBreakdownItemDto("inProgress", "Yurutuluyor", inProgressJobs, percentage(inProgressJobs, totalJobs)),
                new OperationAnalyticsBreakdownItemDto("completed", "Tamamlandi", completedCallJobs, percentage(completedCallJobs, totalJobs)),
                new OperationAnalyticsBreakdownItemDto("failed", "Basarisiz", failedCallJobs, percentage(failedCallJobs, totalJobs)),
                new OperationAnalyticsBreakdownItemDto("skipped", "Atlandi", skippedCallJobs, percentage(skippedCallJobs, totalJobs))
        );

        List<OperationAnalyticsQuestionSummaryDto> questionSummaries = questions.stream()
                .map(question -> buildQuestionSummary(
                        question,
                        optionsByQuestionId.getOrDefault(question.getId(), List.of()),
                        answersByQuestionId.getOrDefault(question.getId(), List.of()),
                        respondedContacts,
                        responsesById
                ))
                .toList();
        questionSummaries = enrichQuestionDropOffs(questionSummaries);
        List<OperationAnalyticsQuestionGroupSummaryDto> questionGroups = buildQuestionGroups(
                questions,
                optionsByQuestionId,
                answersByQuestionId,
                respondedContacts
        );

        List<OperationAnalyticsAudienceBreakdownDto> audienceBreakdowns = buildAudienceBreakdowns(
                questions,
                optionsByQuestionId,
                answersByQuestionId,
                totalContacts
        );
        List<OperationAnalyticsBreakdownItemDto> consentBreakdown = buildConsentBreakdown(responses, totalContacts);

        Map<String, Long> trendMap = responses.stream()
                .map(response -> response.completedAt() != null ? response.completedAt() : response.startedAt())
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        timestamp -> timestamp.toLocalDate()
                                .format(DateTimeFormatter.ISO_LOCAL_DATE),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
        List<OperationAnalyticsTrendPointDto> responseTrend = trendMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new OperationAnalyticsTrendPointDto(entry.getKey(), entry.getValue()))
                .toList();

        boolean partialData = operation.getStatus() == OperationStatus.RUNNING
                || operation.getStatus() == OperationStatus.FAILED
                || partialResponses > 0
                || queuedJobs > 0
                || totalContacts > respondedContacts;

        List<OperationAnalyticsInsightItemDto> insightItems = buildInsightItems(
                totalContacts,
                totalCallsAttempted,
                completedResponses,
                partialResponses,
                failedCallJobs,
                responseRate,
                contactReachRate,
                averageCompletionPercent,
                questionSummaries
        );

        log.info(
                "Generated analytics for operation {}: contacts={}, jobs={}, responses={}, completedResponses={}",
                operationId,
                totalContacts,
                totalJobs,
                allResponses.size(),
                completedResponses
        );

        return new OperationAnalyticsResponseDto(
                operation.getId(),
                totalContacts,
                totalJobs,
                totalJobs,
                totalCallsAttempted,
                completedCallJobs,
                queuedJobs,
                inProgressJobs,
                completedCallJobs,
                failedCallJobs,
                skippedCallJobs,
                allResponses.size(),
                respondedContacts,
                completedResponses,
                partialResponses,
                abandonedResponses,
                invalidResponses,
                completionRate,
                responseRate,
                personResponseRate,
                contactReachRate,
                participationRate,
                averageCompletionPercent,
                partialData,
                buildInsightSummary(operation, totalContacts, completedResponses, partialResponses, failedCallJobs, responseRate),
                insightItems,
                outcomeBreakdown,
                consentBreakdown,
                audienceBreakdowns,
                questionSummaries,
                questionGroups,
                responseTrend
        );
    }

    private void validateRequest(CreateOperationRequest request) {
        Set<ConstraintViolation<CreateOperationRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations.iterator().next().getMessage());
        }
    }

    private void ensureCompanyExists(UUID companyId) {
        if (!companyRepository.existsById(companyId)) {
            throw new NotFoundException("Company not found: " + companyId);
        }
    }

    private AppUser resolveUserForCompany(UUID companyId, UUID userId) {
        if (userId == null) {
            AppUser authenticatedUser = requestAuthContext.requireUser();
            return authenticatedUser.getCompany().getId().equals(companyId) ? authenticatedUser : null;
        }

        return appUserRepository.findById(userId)
                .filter(user -> user.getCompany().getId().equals(companyId))
                .orElseThrow(() -> new ValidationException("Created by user does not belong to company"));
    }

    private Operation getOperation(UUID companyId, UUID operationId) {
        return operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId)
                .orElseThrow(() -> new NotFoundException("Operation not found for company: " + operationId));
    }

    private String buildOperationSourcePayload(Survey survey, String operationName) {
        List<SurveyQuestion> questions = surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(survey.getId());
        Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId = new LinkedHashMap<>();
        if (!questions.isEmpty()) {
            List<UUID> questionIds = questions.stream().map(SurveyQuestion::getId).toList();
            surveyQuestionOptionRepository
                    .findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(questionIds)
                    .forEach(option -> optionsByQuestionId
                            .computeIfAbsent(option.getSurveyQuestion().getId(), ignored -> new ArrayList<>())
                            .add(option));
        }

        return operationAutoEntityLexiconService.buildSourcePayloadJson(
                survey,
                operationName,
                questions,
                optionsByQuestionId,
                null
        );
    }

    private long countContacts(Operation operation) {
        return operationContactRepository.countByOperation_IdAndCompany_IdAndDeletedAtIsNull(
                operation.getId(),
                operation.getCompany().getId()
        );
    }

    private void syncLifecycleState(Operation operation, long contactCount) {
        if (operation.getStatus() == OperationStatus.RUNNING
                || operation.getStatus() == OperationStatus.COMPLETED
                || operation.getStatus() == OperationStatus.FAILED
                || operation.getStatus() == OperationStatus.CANCELLED
                || operation.getStatus() == OperationStatus.PAUSED
                || operation.getStatus() == OperationStatus.SCHEDULED) {
            return;
        }

        OperationStatus nextStatus = buildReadiness(operation, contactCount).readyToStart()
                ? OperationStatus.READY
                : OperationStatus.DRAFT;

        if (operation.getStatus() != nextStatus) {
            operation.setStatus(nextStatus);
            operationRepository.save(operation);
        }
    }

    private OperationReadinessDto buildReadiness(Operation operation, long contactCount) {
        boolean surveyLinked = operation.getSurvey() != null;
        boolean surveyPublished = surveyLinked && operation.getSurvey().getStatus() == SurveyStatus.PUBLISHED;
        boolean contactsLoaded = contactCount > 0;
        boolean startableState = STARTABLE_STATUSES.contains(operation.getStatus());

        List<String> blockingReasons = new ArrayList<>();
        if (!surveyLinked) {
            blockingReasons.add("Operasyona bagli bir anket bulunmuyor.");
        }
        if (surveyLinked && !surveyPublished) {
            blockingReasons.add("Bagli anket yayinlanmis durumda degil.");
        }
        if (!contactsLoaded) {
            blockingReasons.add("Operasyonu baslatmak icin en az bir kisi gerekli.");
        }
        if (!startableState) {
            blockingReasons.add(buildStateBlockingReason(operation.getStatus()));
        }

        return new OperationReadinessDto(
                surveyLinked,
                surveyPublished,
                contactsLoaded,
                startableState,
                blockingReasons.isEmpty(),
                List.copyOf(blockingReasons)
        );
    }

    private OperationExecutionSummaryDto buildExecutionSummary(Operation operation, List<CallJob> newJobs) {
        List<CallJob> callJobs = callJobRepository.findAllByOperation_IdAndDeletedAtIsNull(operation.getId());
        long pendingCount = callJobs.stream()
                .filter(job -> OPEN_CALL_JOB_STATUSES.contains(job.getStatus()))
                .count();
        long completedCount = callJobs.stream()
                .filter(job -> job.getStatus() == CallJobStatus.COMPLETED)
                .count();

        return new OperationExecutionSummaryDto(
                callJobs.size(),
                pendingCount,
                completedCount,
                newJobs.size()
        );
    }

    private OperationResponseDto toDto(Operation operation, long contactCount, List<CallJob> newJobs) {
        return new OperationResponseDto(
                operation.getId(),
                operation.getCompany().getId(),
                operation.getSurvey().getId(),
                operation.getName(),
                operation.getStatus(),
                operation.getSourceType(),
                operation.getSourcePayloadJson(),
                operation.getScheduledAt(),
                operation.getStartedAt(),
                operation.getCompletedAt(),
                operation.getCreatedBy() != null ? operation.getCreatedBy().getId() : null,
                operation.getCreatedAt(),
                operation.getUpdatedAt(),
                buildReadiness(operation, contactCount),
                buildExecutionSummary(operation, newJobs)
        );
    }

    // A contact called more than once (retry, redial) has one response per call. Counting all of them
    // made the contact count several times in question distributions, rates and the consent breakdown.
    // The completed response counts, otherwise the most complete one, otherwise the latest.
    private List<SurveyResponseAnalyticsRow> selectOneResponsePerContact(List<SurveyResponseAnalyticsRow> responses) {
        Comparator<SurveyResponseAnalyticsRow> preference = Comparator
                .comparing((SurveyResponseAnalyticsRow response) -> response.status() == SurveyResponseStatus.COMPLETED)
                .thenComparing(response -> response.completionPercent() == null ? BigDecimal.ZERO : response.completionPercent())
                .thenComparing(this::resolveResponseMoment, Comparator.nullsFirst(Comparator.naturalOrder()));
        Map<UUID, SurveyResponseAnalyticsRow> responseByContact = new LinkedHashMap<>();
        for (SurveyResponseAnalyticsRow response : responses) {
            responseByContact.merge(
                    response.operationContactId(),
                    response,
                    (current, candidate) -> preference.compare(candidate, current) > 0 ? candidate : current
            );
        }
        return List.copyOf(responseByContact.values());
    }

    private long countDistinctResponseContactsByStatus(
            List<SurveyResponseAnalyticsRow> responses,
            Set<UUID> usableResponseIds,
            SurveyResponseStatus status
    ) {
        return responses.stream()
                .filter(response -> response.status() == status)
                .filter(response -> usableResponseIds.contains(response.id()))
                .map(SurveyResponseAnalyticsRow::operationContactId)
                .distinct()
                .count();
    }

    private boolean hasUsableAnalyticsAnswer(SurveyAnswer answer) {
        return hasUsableRatingAnswer(answer)
                || hasUsableNumberAnswer(answer)
                || hasUsableOpenEndedAnswer(answer)
                || hasReadableChoiceFallback(answer)
                || answer.getSelectedOption() != null
                || hasStructuredChoiceFallback(answer.getAnswerJson());
    }

    private OperationAnalyticsQuestionSummaryDto buildQuestionSummary(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            List<SurveyAnswer> answers,
            long respondedContacts,
            Map<UUID, SurveyResponseAnalyticsRow> responsesById
    ) {
        if (question.getQuestionType() == QuestionType.RATING) {
            List<SurveyAnswer> ratingAnswers = answers.stream()
                    .filter(answer -> answer.getAnswerType() == QuestionType.RATING)
                    .filter(this::hasUsableRatingAnswer)
                    .toList();
            List<SurveyAnswer> ratingSpecialAnswers = answers.stream()
                    .filter(answer -> answer.getAnswerType() == QuestionType.RATING)
                    .filter(answer -> resolveSpecialAnswerCode(answer) != null)
                    .toList();
            long answeredCount = ratingAnswers.size() + ratingSpecialAnswers.size();
            double responseRate = percentage(answeredCount, respondedContacts);
            Map<String, Long> ratings = new LinkedHashMap<>();
            for (SurveyAnswer answer : ratingAnswers) {
                BigDecimal ratingValue = resolveRatingValue(answer);
                if (ratingValue == null) {
                    continue;
                }
                String key = String.valueOf(ratingValue.stripTrailingZeros().toPlainString());
                ratings.merge(key, 1L, Long::sum);
            }
            double averageRating = round(ratingAnswers.stream()
                    .map(this::resolveRatingValue)
                    .filter(Objects::nonNull)
                    .mapToDouble(BigDecimal::doubleValue)
                    .average()
                    .orElse(0));
            return new OperationAnalyticsQuestionSummaryDto(
                    question.getId(),
                    question.getCode(),
                    question.getQuestionOrder(),
                    question.getTitle(),
                    question.getQuestionType(),
                    "RATING",
                    respondedContacts,
                    answeredCount,
                    responseRate,
                    0,
                    0,
                    averageRating,
                    answeredCount == 0 ? "Puan dagilimi, gorusmelerden sayisal cevap geldikce gosterilir." : null,
                    0,
                    List.of(),
                    toBreakdown(ratings, answeredCount),
                    buildSpecialAnswerBreakdown(ratingSpecialAnswers, answeredCount),
                    List.of(),
                    List.of()
            );
        }

        if (question.getQuestionType() == QuestionType.NUMBER) {
            List<SurveyAnswer> numberAnswers = answers.stream()
                    .filter(answer -> answer.getAnswerType() == QuestionType.NUMBER)
                    .filter(this::hasUsableNumberAnswer)
                    .toList();
            long answeredCount = numberAnswers.size();
            double responseRate = percentage(answeredCount, respondedContacts);
            Map<String, Long> values = new LinkedHashMap<>();
            for (SurveyAnswer answer : numberAnswers) {
                BigDecimal numericValue = resolveNumericValue(answer);
                if (numericValue == null) {
                    continue;
                }
                String key = numericValue.stripTrailingZeros().toPlainString();
                values.merge(key, 1L, Long::sum);
            }
            double averageValue = round(numberAnswers.stream()
                    .map(this::resolveNumericValue)
                    .filter(Objects::nonNull)
                    .mapToDouble(BigDecimal::doubleValue)
                    .average()
                    .orElse(0));
            return new OperationAnalyticsQuestionSummaryDto(
                    question.getId(),
                    question.getCode(),
                    question.getQuestionOrder(),
                    question.getTitle(),
                    question.getQuestionType(),
                    "NUMBER",
                    respondedContacts,
                    answeredCount,
                    responseRate,
                    0,
                    0,
                    averageValue,
                    answeredCount == 0 ? "Sayisal dagilim, gecerli sayi yanitlari geldikce gosterilir." : null,
                    0,
                    List.of(),
                    toBreakdown(values, answeredCount),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        if (question.getQuestionType() == QuestionType.OPEN_ENDED) {
            List<SurveyAnswer> openEndedAnswers = answers.stream()
                    .filter(answer -> answer.getAnswerType() == QuestionType.OPEN_ENDED)
                    .filter(SurveyAnswer::isValid)
                    .filter(this::hasUsableOpenEndedAnswer)
                    .toList();
            List<SurveyAnswer> specialOpenEndedAnswers = openEndedAnswers.stream()
                    .filter(answer -> resolveSpecialAnswerCode(answer) != null)
                    .toList();
            List<SurveyAnswer> qualitativeAnswers = openEndedAnswers.stream()
                    .filter(answer -> resolveSpecialAnswerCode(answer) == null)
                    .toList();
            if (isNamedEntityOpenEndedQuestion(question)) {
                Map<String, Long> entityCounts = new LinkedHashMap<>();
                List<OperationAnalyticsSampleResponseDto> rawResponses = new ArrayList<>();
                for (SurveyAnswer answer : sortOpenEndedAnswers(qualitativeAnswers, responsesById)) {
                    OperationAnalyticsSampleResponseDto rawResponse = toOpenEndedSampleResponse(
                            answer,
                            new OpenEndedGroupingResolution(null, List.of(), null),
                            responsesById
                    );
                    if (rawResponse != null) {
                        rawResponses.add(rawResponse);
                    }
                    String entityLabel = resolveNamedEntityLabel(answer);
                    if (entityLabel != null) {
                        entityCounts.merge(entityLabel, 1L, Long::sum);
                    }
                }
                long answeredCount = openEndedAnswers.size();
                double responseRate = percentage(answeredCount, respondedContacts);
                return new OperationAnalyticsQuestionSummaryDto(
                        question.getId(),
                        question.getCode(),
                        question.getQuestionOrder(),
                        question.getTitle(),
                        question.getQuestionType(),
                        "OPEN_ENDED",
                        respondedContacts,
                        answeredCount,
                        responseRate,
                        0,
                        0,
                        null,
                        answeredCount == 0 ? "Isim yanitlari geldikce dagilim burada gosterilir." : null,
                        0,
                        List.of(),
                        toBreakdown(entityCounts, answeredCount == 0 ? 1 : answeredCount),
                        buildSpecialAnswerBreakdown(specialOpenEndedAnswers, answeredCount),
                        List.of(),
                        rawResponses
                );
            }
            List<String> reviewGroupOptions = extractConfiguredOpenEndedThemeCodes(question);
            Map<String, Long> confirmedThemeCounts = new LinkedHashMap<>();
            List<OperationAnalyticsSampleResponseDto> reviewSamples = new ArrayList<>();
            List<OperationAnalyticsSampleResponseDto> rawResponses = new ArrayList<>();
            for (SurveyAnswer answer : sortOpenEndedAnswers(qualitativeAnswers, responsesById)) {
                OpenEndedGroupingResolution resolution = resolveOpenEndedGrouping(question, answer);
                OperationAnalyticsSampleResponseDto rawResponse = toOpenEndedSampleResponse(answer, resolution, responsesById);
                if (rawResponse != null) {
                    rawResponses.add(rawResponse);
                }
                if (resolution.confirmedThemeCode() != null) {
                    confirmedThemeCounts.merge(toDisplayLabel(resolution.confirmedThemeCode().replace('_', ' ')), 1L, Long::sum);
                    continue;
                }
                OperationAnalyticsSampleResponseDto reviewSample = rawResponse;
                if (reviewSample != null) {
                    reviewSamples.add(reviewSample);
                }
            }
            long answeredCount = openEndedAnswers.size();
            long confirmedCount = confirmedThemeCounts.values().stream().mapToLong(Long::longValue).sum();
            double responseRate = percentage(answeredCount, respondedContacts);
            return new OperationAnalyticsQuestionSummaryDto(
                    question.getId(),
                    question.getCode(),
                    question.getQuestionOrder(),
                    question.getTitle(),
                    question.getQuestionType(),
                    "OPEN_ENDED",
                    respondedContacts,
                    answeredCount,
                    responseRate,
                    0,
                    0,
                    null,
                    answeredCount == 0 ? "Acik uclu icgoruler, gecerli metin yanitlari geldikce olusur." : null,
                    reviewSamples.size(),
                    reviewGroupOptions,
                    toBreakdown(confirmedThemeCounts, confirmedCount == 0 ? 1 : confirmedCount),
                    buildSpecialAnswerBreakdown(specialOpenEndedAnswers, answeredCount),
                    reviewSamples,
                    rawResponses
            );
        }

        List<SurveyAnswer> choiceAnswers = answers.stream()
                .filter(answer -> answer.getAnswerType() == question.getQuestionType())
                .toList();
        List<List<String>> choiceSelections = resolveChoiceSelections(question, options, choiceAnswers);
        Map<String, Long> distributions = buildChoiceDistribution(options, choiceSelections);
        List<SurveyAnswer> specialChoiceAnswers = choiceAnswers.stream()
                .filter(answer -> resolveSpecialAnswerCode(answer) != null)
                .filter(answer -> resolveChoiceLabels(question, answer, options).isEmpty())
                .toList();
        long answeredCount = choiceSelections.size() + specialChoiceAnswers.size();
        double responseRate = percentage(answeredCount, respondedContacts);
        String chartKind = isBinaryQuestion(options) ? "BINARY" : question.getQuestionType() == QuestionType.MULTI_CHOICE
                ? "MULTI_CHOICE"
                : "CHOICE";

        return new OperationAnalyticsQuestionSummaryDto(
                question.getId(),
                question.getCode(),
                question.getQuestionOrder(),
                question.getTitle(),
                question.getQuestionType(),
                chartKind,
                respondedContacts,
                answeredCount,
                responseRate,
                0,
                0,
                null,
                answeredCount == 0 ? "Bu soru icin dagilim, cevaplar geldikce burada gosterilir." : null,
                0,
                List.of(),
                toBreakdown(distributions, answeredCount == 0 ? 1 : answeredCount),
                buildSpecialAnswerBreakdown(specialChoiceAnswers, answeredCount),
                List.of(),
                List.of()
        );
    }

    private Map<String, Long> buildChoiceDistribution(List<SurveyQuestionOption> options, List<List<String>> selections) {
        Map<String, Long> counts = new LinkedHashMap<>();
        options.forEach(option -> counts.put(option.getLabel(), 0L));

        for (List<String> selection : selections) {
            selection.forEach(label -> counts.computeIfPresent(label, (key, value) -> value + 1L));
        }

        return counts;
    }

    private List<OperationAnalyticsQuestionSummaryDto> enrichQuestionDropOffs(
            List<OperationAnalyticsQuestionSummaryDto> questionSummaries
    ) {
        List<OperationAnalyticsQuestionSummaryDto> items = new ArrayList<>(questionSummaries.size());

        for (int index = 0; index < questionSummaries.size(); index++) {
            OperationAnalyticsQuestionSummaryDto current = questionSummaries.get(index);
            OperationAnalyticsQuestionSummaryDto next = index + 1 < questionSummaries.size()
                    ? questionSummaries.get(index + 1)
                    : null;

            long dropOffCount = next == null
                    ? 0
                    : Math.max(current.answeredCount() - next.answeredCount(), 0);
            double dropOffRate = percentage(dropOffCount, current.answeredCount());

            items.add(new OperationAnalyticsQuestionSummaryDto(
                    current.questionId(),
                    current.questionCode(),
                    current.questionOrder(),
                    current.questionTitle(),
                    current.questionType(),
                    current.chartKind(),
                    current.respondedContactCount(),
                    current.answeredCount(),
                    current.responseRate(),
                    dropOffCount,
                    dropOffRate,
                    current.averageRating(),
                    current.emptyStateMessage(),
                    current.reviewCount(),
                    current.reviewGroupOptions(),
                    current.breakdown(),
                    current.specialAnswerBreakdown(),
                    current.sampleResponses(),
                    current.rawResponses()
            ));
        }

        return items;
    }

    private List<OperationAnalyticsQuestionGroupSummaryDto> buildQuestionGroups(
            List<SurveyQuestion> questions,
            Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId,
            Map<UUID, List<SurveyAnswer>> answersByQuestionId,
            long respondedContacts
    ) {
        Map<String, List<SurveyQuestion>> questionsByGroupCode = questions.stream()
                .filter(this::isGroupedChoiceQuestion)
                .collect(Collectors.groupingBy(
                        question -> readGroupingMetadata(question).groupCode(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<OperationAnalyticsQuestionGroupSummaryDto> groups = new ArrayList<>();
        for (List<SurveyQuestion> groupedQuestions : questionsByGroupCode.values()) {
            if (groupedQuestions.size() < 2) {
                continue;
            }
            groups.add(buildQuestionGroupSummary(groupedQuestions, optionsByQuestionId, answersByQuestionId, respondedContacts));
        }
        return groups;
    }

    private OperationAnalyticsQuestionGroupSummaryDto buildQuestionGroupSummary(
            List<SurveyQuestion> groupedQuestions,
            Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId,
            Map<UUID, List<SurveyAnswer>> answersByQuestionId,
            long respondedContacts
    ) {
        List<SurveyQuestion> sortedQuestions = groupedQuestions.stream()
                .sorted(Comparator.comparing(SurveyQuestion::getQuestionOrder))
                .toList();
        QuestionGroupingMetadata grouping = readGroupingMetadata(sortedQuestions.getFirst());
        List<SurveyQuestionOption> sharedOptions = optionsByQuestionId.getOrDefault(sortedQuestions.getFirst().getId(), List.of());

        List<OperationAnalyticsQuestionGroupRowDto> rows = new ArrayList<>();
        List<List<String>> selectionsByRow = new ArrayList<>();
        long answeredRowCount = 0;

        for (SurveyQuestion question : sortedQuestions) {
            List<SurveyQuestionOption> options = optionsByQuestionId.getOrDefault(question.getId(), sharedOptions);
            List<List<String>> selections = resolveChoiceSelections(
                    question,
                    options,
                    answersByQuestionId.getOrDefault(question.getId(), List.of())
            );
            QuestionGroupingMetadata metadata = readGroupingMetadata(question);
            long answeredCount = selections.size();
            answeredRowCount += answeredCount;
            rows.add(new OperationAnalyticsQuestionGroupRowDto(
                    question.getId(),
                    question.getCode(),
                    question.getQuestionOrder(),
                    metadata.rowKey(),
                    metadata.rowLabel(),
                    answeredCount,
                    percentage(answeredCount, respondedContacts)
            ));
            selectionsByRow.add(selections.stream().flatMap(Collection::stream).toList());
        }

        List<OperationAnalyticsQuestionGroupSeriesDto> series = new ArrayList<>();
        for (SurveyQuestionOption option : sharedOptions) {
            List<Long> data = new ArrayList<>(selectionsByRow.size());
            for (List<String> rowSelections : selectionsByRow) {
                long count = rowSelections.stream()
                        .filter(label -> normalize(label).equals(normalize(option.getLabel())))
                        .count();
                data.add(count);
            }
            series.add(new OperationAnalyticsQuestionGroupSeriesDto(
                    option.getOptionCode(),
                    option.getLabel(),
                    List.copyOf(data)
            ));
        }

        String emptyStateMessage = answeredRowCount == 0
                ? "Bu soru grubu icin birlesik dagilim, cevaplar geldikce burada gosterilir."
                : null;
        return new OperationAnalyticsQuestionGroupSummaryDto(
                grouping.groupCode(),
                grouping.groupTitle(),
                sortedQuestions.getFirst().getQuestionType() == QuestionType.MULTI_CHOICE ? "GROUPED_MULTI_CHOICE" : "GROUPED_CHOICE",
                grouping.optionSetCode(),
                respondedContacts,
                answeredRowCount,
                emptyStateMessage,
                List.copyOf(rows),
                List.copyOf(series)
        );
    }

    private boolean isGroupedChoiceQuestion(SurveyQuestion question) {
        if (question.getQuestionType() != QuestionType.SINGLE_CHOICE
                && question.getQuestionType() != QuestionType.MULTI_CHOICE
                && question.getQuestionType() != QuestionType.RATING) {
            return false;
        }
        return readGroupingMetadata(question).groupCode() != null;
    }

    private QuestionGroupingMetadata readGroupingMetadata(SurveyQuestion question) {
        String rowLabel = question.getTitle();
        String rowKey = slugify(question.getCode() != null ? question.getCode() : question.getTitle());
        if (question.getSettingsJson() == null || question.getSettingsJson().isBlank()) {
            return new QuestionGroupingMetadata(null, null, rowKey, rowLabel, null);
        }
        try {
            JsonNode root = objectMapper.readTree(question.getSettingsJson());
            String groupCode = trimToNull(text(root, "groupCode", "group_code"));
            String groupTitle = trimToNull(text(root, "groupTitle", "group_title"));
            String configuredRowKey = trimToNull(text(root, "rowKey", "row_key", "rowCode", "row_code"));
            String configuredRowLabel = trimToNull(text(root, "rowLabel", "row_label"));
            String optionSetCode = trimToNull(text(root, "optionSetCode", "option_set_code"));
            return new QuestionGroupingMetadata(
                    groupCode,
                    groupTitle != null ? groupTitle : question.getTitle(),
                    configuredRowKey != null ? configuredRowKey : rowKey,
                    configuredRowLabel != null ? configuredRowLabel : rowLabel,
                    optionSetCode
            );
        } catch (Exception error) {
            return new QuestionGroupingMetadata(null, null, rowKey, rowLabel, null);
        }
    }

    private List<OperationAnalyticsAudienceBreakdownDto> buildAudienceBreakdowns(
            List<SurveyQuestion> questions,
            Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId,
            Map<UUID, List<SurveyAnswer>> answersByQuestionId,
            long totalContacts
    ) {
        List<OperationAnalyticsAudienceBreakdownDto> items = new ArrayList<>();

        addAudienceBreakdown(items, "gender", "Cinsiyet", questions, optionsByQuestionId, answersByQuestionId, totalContacts);
        addAudienceBreakdown(items, "city", "Sehir", questions, optionsByQuestionId, answersByQuestionId, totalContacts);
        addAudienceBreakdown(items, "age", "Yas", questions, optionsByQuestionId, answersByQuestionId, totalContacts);

        return items;
    }

    private void addAudienceBreakdown(
            List<OperationAnalyticsAudienceBreakdownDto> items,
            String dimension,
            String label,
            List<SurveyQuestion> questions,
            Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId,
            Map<UUID, List<SurveyAnswer>> answersByQuestionId,
            long totalContacts
    ) {
        SurveyQuestion question = questions.stream()
                .filter(item -> matchesAudienceDimension(item, dimension))
                .findFirst()
                .orElse(null);
        if (question == null) {
            return;
        }

        List<SurveyQuestionOption> options = optionsByQuestionId.getOrDefault(question.getId(), List.of());
        List<SurveyAnswer> answers = answersByQuestionId.getOrDefault(question.getId(), List.of());
        List<OperationAnalyticsBreakdownItemDto> breakdown = switch (dimension) {
            case "gender" -> buildGenderBreakdown(question, options, answers);
            case "city" -> buildCityBreakdown(question, options, answers);
            case "age" -> buildAgeBreakdown(question, options, answers);
            default -> List.of();
        };

        long answeredCount = answers.stream()
                .map(answer -> resolveAudienceValue(question, answer, options, dimension))
                .filter(Objects::nonNull)
                .count();

        items.add(new OperationAnalyticsAudienceBreakdownDto(
                dimension,
                label,
                question.getCode(),
                question.getTitle(),
                answeredCount,
                breakdown
        ));
    }

    private boolean matchesAudienceDimension(SurveyQuestion question, String dimension) {
        String code = normalize(question.getCode());
        String title = normalize(question.getTitle());

        return switch (dimension) {
            case "gender" -> code.contains("gender")
                    || code.contains("cinsiyet")
                    || title.contains("gender")
                    || title.contains("cinsiyet");
            case "city" -> code.contains("city")
                    || code.contains("sehir")
                    || code.contains("şehir")
                    || code.contains("il")
                    || title.contains("city")
                    || title.contains("sehir")
                    || title.contains("şehir")
                    || title.contains("yasadiginiz sehir")
                    || title.contains("yasadiginiz il");
            case "age" -> code.contains("age")
                    || code.contains("yas")
                    || code.contains("yaş")
                    || title.contains("age")
                    || title.contains("yas")
                    || title.contains("yaş");
            default -> false;
        };
    }

    private List<OperationAnalyticsBreakdownItemDto> buildGenderBreakdown(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            List<SurveyAnswer> answers
    ) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (SurveyAnswer answer : answers) {
            String value = resolveAudienceValue(question, answer, options, "gender");
            if (value == null) {
                continue;
            }

            counts.merge(value, 1L, Long::sum);
        }
        return toBreakdown(counts, counts.values().stream().mapToLong(Long::longValue).sum());
    }

    private List<OperationAnalyticsBreakdownItemDto> buildCityBreakdown(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            List<SurveyAnswer> answers
    ) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (SurveyAnswer answer : answers) {
            String value = resolveAudienceValue(question, answer, options, "city");
            if (value == null) {
                continue;
            }
            counts.merge(value, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(8)
                .map(entry -> new OperationAnalyticsBreakdownItemDto(
                        slugify(entry.getKey()),
                        entry.getKey(),
                        entry.getValue(),
                        percentage(entry.getValue(), counts.values().stream().mapToLong(Long::longValue).sum())
                ))
                .toList();
    }

    private List<OperationAnalyticsBreakdownItemDto> buildAgeBreakdown(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            List<SurveyAnswer> answers
    ) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("18 alti", 0L);
        counts.put("18-24", 0L);
        counts.put("25-34", 0L);
        counts.put("35-44", 0L);
        counts.put("45-54", 0L);
        counts.put("55+", 0L);

        Map<String, Long> fallbackLabels = new LinkedHashMap<>();

        for (SurveyAnswer answer : answers) {
            Integer age = resolveAgeValue(answer, options);
            if (age != null) {
                counts.merge(resolveAgeBucket(age), 1L, Long::sum);
                continue;
            }

            String fallback = resolveAudienceValue(question, answer, options, "age");
            if (fallback != null) {
                fallbackLabels.merge(fallback, 1L, Long::sum);
            }
        }

        long numericTotal = counts.values().stream().mapToLong(Long::longValue).sum();
        if (numericTotal > 0) {
            return toBreakdown(counts, numericTotal);
        }

        return toBreakdown(fallbackLabels, fallbackLabels.values().stream().mapToLong(Long::longValue).sum());
    }

    private Integer resolveAgeValue(SurveyAnswer answer, List<SurveyQuestionOption> options) {
        BigDecimal numericValue = resolveNumericValue(answer);
        if (numericValue != null) {
            int age = numericValue.intValue();
            if (age >= 0 && age <= 120) {
                return age;
            }
        }

        List<String> labels = resolveChoiceLabels(answer.getSurveyQuestion(), answer, options);
        for (String label : labels) {
            Integer parsed = extractAgeNumber(label);
            if (parsed != null) {
                return parsed;
            }
        }

        return extractAgeNumber(resolveOpenEndedText(answer));
    }

    private Integer extractAgeNumber(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        String digits = rawValue.replaceAll("[^0-9]", " ").trim();
        if (digits.isBlank()) {
            return null;
        }

        String firstNumber = digits.split("\\s+")[0];
        try {
            int value = Integer.parseInt(firstNumber);
            return value >= 0 && value <= 120 ? value : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private String resolveAgeBucket(int age) {
        if (age < 18) {
            return "18 alti";
        }
        if (age <= 24) {
            return "18-24";
        }
        if (age <= 34) {
            return "25-34";
        }
        if (age <= 44) {
            return "35-44";
        }
        if (age <= 54) {
            return "45-54";
        }
        return "55+";
    }

    private String resolveAudienceValue(
            SurveyQuestion question,
            SurveyAnswer answer,
            List<SurveyQuestionOption> options,
            String dimension
    ) {
        if (question.getQuestionType() == QuestionType.SINGLE_CHOICE || question.getQuestionType() == QuestionType.MULTI_CHOICE) {
            List<String> labels = resolveChoiceLabels(question, answer, options);
            if (!labels.isEmpty()) {
                return normalizeAudienceLabel(labels.get(0), dimension);
            }
        }

        if ((question.getQuestionType() == QuestionType.RATING || question.getQuestionType() == QuestionType.NUMBER) && "age".equals(dimension)) {
            Integer age = resolveAgeValue(answer, options);
            return age == null ? null : resolveAgeBucket(age);
        }

        String text = resolveOpenEndedText(answer);
        return normalizeAudienceLabel(text, dimension);
    }

    private String normalizeAudienceLabel(String rawValue, String dimension) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        String trimmed = rawValue.trim();
        String normalized = normalize(trimmed);

        if ("gender".equals(dimension)) {
            if (normalized.equals("erkek") || normalized.equals("male") || normalized.equals("man")) {
                return "Erkek";
            }
            if (normalized.equals("kadin") || normalized.equals("kadın") || normalized.equals("female") || normalized.equals("woman")) {
                return "Kadin";
            }
        }

        if ("city".equals(dimension)) {
            return toDisplayLabel(trimmed);
        }

        if ("age".equals(dimension)) {
            Integer age = extractAgeNumber(trimmed);
            return age == null ? toDisplayLabel(trimmed) : resolveAgeBucket(age);
        }

        return toDisplayLabel(trimmed);
    }

    private String toDisplayLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.substring(0, 1).toUpperCase(Locale.forLanguageTag("tr"))
                + (trimmed.length() > 1 ? trimmed.substring(1) : "");
    }

    private List<List<String>> resolveChoiceSelections(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            List<SurveyAnswer> answers
    ) {
        return answers.stream()
                .filter(answer -> answer.getAnswerType() == question.getQuestionType())
                .map(answer -> {
                    List<String> labels = resolveChoiceLabels(question, answer, options).stream().distinct().toList();
                    return new ResolvedChoiceAnswer(answer, labels);
                })
                .filter(resolved -> isUsableChoiceAnswer(resolved.answer(), resolved.labels()))
                .map(ResolvedChoiceAnswer::labels)
                .filter(labels -> question.getQuestionType() == QuestionType.MULTI_CHOICE || labels.size() == 1)
                .toList();
    }

    private List<String> resolveChoiceLabels(SurveyQuestion question, SurveyAnswer answer, List<SurveyQuestionOption> options) {
        if (answer.getSelectedOption() != null) {
            String selectedLabel = answer.getSelectedOption().getLabel();
            return options.stream()
                    .map(SurveyQuestionOption::getLabel)
                    .filter(label -> normalize(label).equals(normalize(selectedLabel)))
                    .findFirst()
                    .map(List::of)
                    .orElse(List.of());
        }

        if ((question.getQuestionType() == QuestionType.RATING || question.getQuestionType() == QuestionType.NUMBER) && answer.getAnswerNumber() != null) {
            String numericValue = answer.getAnswerNumber().stripTrailingZeros().toPlainString();
            java.util.Optional<String> numericMatch = resolveOptionLabel(question, numericValue, options);
            if (numericMatch.isPresent()
                    && options.stream().anyMatch(option -> normalize(numericMatch.get()).equals(normalize(option.getLabel())))) {
                return List.of(numericMatch.get());
            }
        }

        List<String> extracted = extractLabels(question, answer.getAnswerJson(), options).stream()
                .filter(label -> options.stream().anyMatch(option -> normalize(label).equals(normalize(option.getLabel()))))
                .toList();
        if (!extracted.isEmpty()) {
            return extracted;
        }

        return java.util.stream.Stream.of(
                        answer.getAnswerText(),
                        answer.getRawInputText()
                )
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> resolveOptionLabel(question, value, options).orElse(null))
                .filter(Objects::nonNull)
                .filter(label -> options.stream().anyMatch(option -> normalize(label).equals(normalize(option.getLabel()))))
                .distinct()
                .toList();
    }

    private List<String> extractLabels(SurveyQuestion question, String rawJson, List<SurveyQuestionOption> options) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(rawJson);
            List<String> labels = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    resolveOptionLabel(question, node.asText(null), options).ifPresent(labels::add);
                }
            } else if (root.has("normalizedValues") && root.get("normalizedValues").isArray()) {
                for (JsonNode node : root.get("normalizedValues")) {
                    resolveOptionLabel(question, node.asText(null), options).ifPresent(labels::add);
                }
            } else if (root.has("selectedOptionIds") && root.get("selectedOptionIds").isArray()) {
                for (JsonNode node : root.get("selectedOptionIds")) {
                    resolveOptionLabel(question, node.asText(null), options).ifPresent(labels::add);
                }
            } else if (root.has("selectedOptionId")) {
                resolveOptionLabel(question, root.get("selectedOptionId").asText(null), options).ifPresent(labels::add);
            } else if (root.has("selectedOptions") && root.get("selectedOptions").isArray()) {
                for (JsonNode node : root.get("selectedOptions")) {
                    resolveOptionLabel(question, node.asText(null), options).ifPresent(labels::add);
                }
            } else if (root.has("normalizedText")) {
                resolveOptionLabel(question, root.get("normalizedText").asText(null), options).ifPresent(labels::add);
            } else if (root.has("value")) {
                resolveOptionLabel(question, root.get("value").asText(null), options).ifPresent(labels::add);
            }
            return labels;
        } catch (Exception error) {
            return List.of();
        }
    }

    private java.util.Optional<String> resolveOptionLabel(
            SurveyQuestion question,
            String rawValue,
            List<SurveyQuestionOption> options
    ) {
        if (rawValue == null || rawValue.isBlank()) {
            return java.util.Optional.empty();
        }
        String normalized = normalize(rawValue);

        java.util.Optional<String> directMatch = options.stream()
                .filter(option -> normalized.equals(normalize(option.getId().toString()))
                        || normalized.equals(normalize(option.getOptionCode()))
                        || normalized.equals(normalize(option.getValue()))
                        || normalized.equals(normalize(option.getLabel())))
                .map(SurveyQuestionOption::getLabel)
                .findFirst();
        if (directMatch.isPresent()) {
            return directMatch;
        }

        java.util.Optional<String> aliasMatch = resolveConfiguredAliasLabel(question, rawValue, options);
        if (aliasMatch.isPresent()) {
            return aliasMatch;
        }

        String simplified = simplifyChoiceText(rawValue);
        if (!simplified.isBlank()) {
            java.util.Optional<String> simplifiedMatch = options.stream()
                    .filter(option -> simplified.equals(simplifyChoiceText(option.getLabel())))
                    .map(SurveyQuestionOption::getLabel)
                    .findFirst();
            if (simplifiedMatch.isPresent()) {
                return simplifiedMatch;
            }
        }

        return java.util.Optional.of(rawValue.trim());
    }

    private java.util.Optional<String> resolveConfiguredAliasLabel(
            SurveyQuestion question,
            String rawValue,
            List<SurveyQuestionOption> options
    ) {
        if (question.getSettingsJson() == null || question.getSettingsJson().isBlank()) {
            return java.util.Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(question.getSettingsJson());
            JsonNode aliasesNode = root.get("aliases");
            if (aliasesNode == null || !aliasesNode.isObject()) {
                return java.util.Optional.empty();
            }

            String normalizedRaw = normalize(rawValue);
            Iterator<String> fieldNames = aliasesNode.fieldNames();
            while (fieldNames.hasNext()) {
                String configuredLabel = fieldNames.next();
                JsonNode aliasValues = aliasesNode.get(configuredLabel);
                if (aliasValues == null || !aliasValues.isArray()) {
                    continue;
                }

                for (JsonNode aliasNode : aliasValues) {
                    String alias = aliasNode.asText(null);
                    if (alias == null || alias.isBlank()) {
                        continue;
                    }
                    if (!normalizedRaw.equals(normalize(alias))) {
                        continue;
                    }

                    return options.stream()
                            .map(SurveyQuestionOption::getLabel)
                            .filter(label -> normalize(label).equals(normalize(configuredLabel)))
                            .findFirst()
                            .or(() -> java.util.Optional.of(configuredLabel));
                }
            }
        } catch (Exception error) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.empty();
    }

    private String simplifyChoiceText(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return normalized;
        }

        String simplified = normalized
                .replaceAll("\\b(cok|çok|biraz|hic|hiç|asla|pek)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return simplified.isBlank() ? normalized : simplified;
    }

    private boolean hasUsableOpenEndedAnswer(SurveyAnswer answer) {
        String text = resolveOpenEndedText(answer);
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = normalize(text);
        return !normalized.isBlank() && !OPEN_ENDED_CONSENT_ARTIFACTS.contains(normalized);
    }

    private List<OperationAnalyticsBreakdownItemDto> buildConsentBreakdown(List<SurveyResponseAnalyticsRow> responses, long totalContacts) {
        if (responses.isEmpty()) {
            return List.of();
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("Katilmayi kabul etti", 0L);
        counts.put("Katilmayi reddetti", 0L);
        counts.put("Belirsiz / yanitsiz", 0L);

        for (SurveyResponseAnalyticsRow response : responses) {
            String consentState = trimToNull(response.consentState());
            // The interview ended without a clear answer; its ABANDONED status is not a refusal.
            if (CONSENT_UNCLEAR.equals(consentState)) {
                counts.computeIfPresent("Belirsiz / yanitsiz", (key, value) -> value + 1L);
            } else if (CONSENT_DECLINED.equals(consentState) || inferConsentDeclined(response)) {
                counts.computeIfPresent("Katilmayi reddetti", (key, value) -> value + 1L);
            } else if (CONSENT_GRANTED.equals(consentState) || inferConsentAccepted(response)) {
                counts.computeIfPresent("Katilmayi kabul etti", (key, value) -> value + 1L);
            } else {
                counts.computeIfPresent("Belirsiz / yanitsiz", (key, value) -> value + 1L);
            }
        }

        long denominator = totalContacts > 0 ? totalContacts : responses.size();
        return toBreakdown(counts, denominator);
    }

    private boolean inferConsentDeclined(SurveyResponseAnalyticsRow response) {
        if (response == null || response.status() != SurveyResponseStatus.ABANDONED) {
            return false;
        }

        BigDecimal completionPercent = response.completionPercent();
        return completionPercent == null || completionPercent.compareTo(BigDecimal.ZERO) <= 0;
    }

    private boolean inferConsentAccepted(SurveyResponseAnalyticsRow response) {
        if (response == null) {
            return false;
        }

        if (response.status() == SurveyResponseStatus.COMPLETED) {
            return true;
        }

        BigDecimal completionPercent = response.completionPercent();
        return completionPercent != null && completionPercent.compareTo(BigDecimal.ZERO) > 0;
    }

    private List<OperationAnalyticsBreakdownItemDto> buildSpecialAnswerBreakdown(List<SurveyAnswer> answers, long answeredCount) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (SurveyAnswer answer : answers) {
            String code = resolveSpecialAnswerCode(answer);
            if (code == null) {
                continue;
            }
            String label = toDisplayLabel(code.replace('_', ' '));
            counts.merge(label, 1L, Long::sum);
        }
        if (counts.isEmpty()) {
            return List.of();
        }
        return toBreakdown(counts, answeredCount == 0 ? 1 : answeredCount);
    }

    private List<String> extractConfiguredOpenEndedThemeCodes(SurveyQuestion question) {
        if (question == null || question.getSettingsJson() == null || question.getSettingsJson().isBlank()) {
            return extractDefaultOpenEndedThemeCodes();
        }
        try {
            JsonNode root = objectMapper.readTree(question.getSettingsJson());
            JsonNode categoriesNode = root.path("coding").path("categories");
            if (categoriesNode.isMissingNode() || categoriesNode.isNull() || !categoriesNode.isObject()) {
                categoriesNode = root.path("codingCategories");
            }
            if (categoriesNode.isMissingNode() || categoriesNode.isNull() || !categoriesNode.isObject()) {
                return extractDefaultOpenEndedThemeCodes();
            }

            List<String> codes = new ArrayList<>();
            categoriesNode.fieldNames().forEachRemaining(fieldName -> {
                String code = normalizeOrNull(fieldName);
                if (code != null && !codes.contains(code)) {
                    codes.add(code);
                }
            });
            return codes.isEmpty() ? extractDefaultOpenEndedThemeCodes() : List.copyOf(codes);
        } catch (Exception error) {
            return extractDefaultOpenEndedThemeCodes();
        }
    }

    private boolean isNamedEntityOpenEndedQuestion(SurveyQuestion question) {
        if (question == null || question.getQuestionType() != QuestionType.OPEN_ENDED) {
            return false;
        }
        if (hasNamedEntityLexicon(question)) {
            return true;
        }
        String normalizedTitle = normalizeOrNull(question.getTitle());
        if (normalizedTitle == null) {
            return false;
        }
        return normalizedTitle.contains("hangi siyasetci")
                || normalizedTitle.contains("hangi politikaci")
                || normalizedTitle.contains("kim oldugunu")
                || normalizedTitle.contains("kimin")
                || normalizedTitle.contains("kimdir")
                || normalizedTitle.contains("adini")
                || normalizedTitle.contains("ismi")
                || normalizedTitle.contains("siyasetciler denince")
                || normalizedTitle.contains("akliniza ilk gelen");
    }

    private boolean hasNamedEntityLexicon(SurveyQuestion question) {
        if (question == null || question.getSettingsJson() == null || question.getSettingsJson().isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(question.getSettingsJson());
            return hasUsableNamedEntityLexiconNode(root.path("autoEntityLexicon"))
                    || hasUsableNamedEntityLexiconNode(root.path("autoLexicon"))
                    || hasUsableNamedEntityLexiconNode(root.path("entityLexicon"));
        } catch (Exception error) {
            return false;
        }
    }

    private boolean hasUsableNamedEntityLexiconNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isArray()) {
            return node.size() > 0;
        }
        return node.isObject() && node.fields().hasNext();
    }

    private OpenEndedGroupingResolution resolveOpenEndedGrouping(SurveyQuestion question, SurveyAnswer answer) {
        List<String> configuredCodes = extractConfiguredOpenEndedThemeCodes(question);
        if (configuredCodes.isEmpty()) {
            return new OpenEndedGroupingResolution(null, List.of(), "Bu soru icin grup kategorileri tanimli degil.");
        }

        List<String> storedCodes = extractStoredThemeCodes(answer).stream()
                .filter(configuredCodes::contains)
                .distinct()
                .toList();
        if (storedCodes.isEmpty()) {
            storedCodes = extractConfiguredOpenEndedThemeMatches(question, resolveRawOpenEndedText(answer)).stream()
                    .filter(configuredCodes::contains)
                    .distinct()
                    .toList();
        }
        if (storedCodes.isEmpty()) {
            storedCodes = extractKeywordBasedOpenEndedThemeMatches(resolveRawOpenEndedText(answer)).stream()
                    .filter(configuredCodes::contains)
                    .distinct()
                    .toList();
        }
        if (storedCodes.size() == 1) {
            return new OpenEndedGroupingResolution(storedCodes.getFirst(), storedCodes, null);
        }
        if (storedCodes.size() > 1) {
            return new OpenEndedGroupingResolution(null, storedCodes, "Birden fazla grup eslesmesi bulundu.");
        }
        return new OpenEndedGroupingResolution(null, List.of(), "Cevap net bir gruba eslestirilemedi.");
    }

    private List<String> extractDefaultOpenEndedThemeCodes() {
        List<String> codes = new ArrayList<>();
        for (String label : OPEN_ENDED_THEME_BY_KEYWORD.values()) {
            String normalized = normalizeOrNull(label);
            if (normalized != null && !codes.contains(normalized)) {
                codes.add(normalized);
            }
        }
        return List.copyOf(codes);
    }

    // Reads the respondent from the already loaded response rows, so a sample costs no extra queries.
    private OperationAnalyticsSampleResponseDto toOpenEndedSampleResponse(
            SurveyAnswer answer,
            OpenEndedGroupingResolution resolution,
            Map<UUID, SurveyResponseAnalyticsRow> responsesById
    ) {
        String responseText = resolveOpenEndedText(answer);
        if (responseText == null || responseText.isBlank()) {
            return null;
        }

        SurveyResponseAnalyticsRow surveyResponse = responsesById.get(answer.getSurveyResponse().getId());
        if (surveyResponse == null || surveyResponse.callJobId() == null) {
            return null;
        }

        String respondentName = buildContactDisplayName(surveyResponse.contactFirstName(), surveyResponse.contactLastName());
        if (respondentName == null) {
            respondentName = "Katilimci";
        }

        return new OperationAnalyticsSampleResponseDto(
                surveyResponse.callJobId(),
                respondentName,
                resolveResponseMoment(surveyResponse),
                trimSample(responseText.trim()),
                trimToNull(resolveRawOpenEndedText(answer)) == null ? trimSample(responseText.trim()) : trimSample(resolveRawOpenEndedText(answer).trim()),
                resolution.suggestedThemeCodes(),
                resolution.reviewReason()
        );
    }

    private String resolveNamedEntityLabel(SurveyAnswer answer) {
        String text = trimToNull(resolveOpenEndedText(answer));
        if (text == null) {
            return null;
        }
        String cleaned = text
                .replaceAll("^[\\p{Punct}\\s]+", "")
                .replaceAll("[\\p{Punct}\\s]+$", "")
                .trim();
        return trimToNull(cleaned);
    }

    private String buildContactDisplayName(String contactFirstName, String contactLastName) {
        String firstName = contactFirstName == null ? "" : contactFirstName.trim();
        String lastName = contactLastName == null ? "" : contactLastName.trim();
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isBlank() ? null : fullName;
    }

    private List<SurveyAnswer> sortOpenEndedAnswers(List<SurveyAnswer> answers, Map<UUID, SurveyResponseAnalyticsRow> responsesById) {
        return answers.stream()
                .sorted(Comparator
                        .comparing(
                                (SurveyAnswer answer) -> resolveResponseMoment(responsesById.get(answer.getSurveyResponse().getId())),
                                Comparator.nullsLast(Comparator.reverseOrder())
                        )
                        .thenComparing(
                                answer -> {
                                    SurveyResponseAnalyticsRow response = responsesById.get(answer.getSurveyResponse().getId());
                                    return response == null ? null : response.createdAt();
                                },
                                Comparator.nullsLast(Comparator.reverseOrder())
                        ))
                .toList();
    }

    private OffsetDateTime resolveResponseMoment(SurveyResponseAnalyticsRow response) {
        if (response == null) {
            return null;
        }
        if (response.completedAt() != null) {
            return response.completedAt();
        }
        if (response.startedAt() != null) {
            return response.startedAt();
        }
        return response.createdAt();
    }

    private List<String> extractStoredThemeCodes(SurveyAnswer answer) {
        if (answer.getAnswerJson() == null || answer.getAnswerJson().isBlank()) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(answer.getAnswerJson());
            JsonNode codedThemes = root.get("codedThemes");
            if (codedThemes == null || !codedThemes.isArray()) {
                return List.of();
            }
            List<String> codes = new ArrayList<>();
            codedThemes.forEach(item -> {
                String rawValue = normalizeOrNull(item.asText(null));
                if (rawValue != null && !codes.contains(rawValue)) {
                    codes.add(rawValue);
                }
            });
            return List.copyOf(codes);
        } catch (Exception error) {
            return List.of();
        }
    }

    private List<String> extractConfiguredOpenEndedThemeMatches(SurveyQuestion question, String text) {
        String normalizedText = normalizeOrNull(text);
        if (normalizedText == null || question == null || question.getSettingsJson() == null || question.getSettingsJson().isBlank()) {
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

            List<String> matches = new ArrayList<>();
            categoriesNode.fields().forEachRemaining(entry -> {
                String code = normalizeOrNull(entry.getKey());
                if (code == null || !entry.getValue().isArray()) {
                    return;
                }
                for (JsonNode aliasNode : entry.getValue()) {
                    String alias = normalizeOrNull(aliasNode.asText(null));
                    if (alias != null && normalizedText.contains(alias)) {
                        if (!matches.contains(code)) {
                            matches.add(code);
                        }
                        break;
                    }
                }
            });
            return List.copyOf(matches);
        } catch (Exception error) {
            return List.of();
        }
    }

    private List<String> extractKeywordBasedOpenEndedThemeMatches(String text) {
        List<String> matches = new ArrayList<>();
        for (String token : extractMeaningfulTokens(text)) {
            String label = OPEN_ENDED_THEME_BY_KEYWORD.get(token);
            if (label == null) {
                continue;
            }
            String code = normalizeOrNull(label);
            if (code != null && !matches.contains(code)) {
                matches.add(code);
            }
        }
        return List.copyOf(matches);
    }

    private List<String> extractMeaningfulTokens(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return List.of();
        }

        return java.util.Arrays.stream(normalized.split("\\s+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .filter(token -> token.length() >= 3)
                .filter(token -> !OPEN_ENDED_STOP_WORDS.contains(token))
                .toList();
    }

    private boolean hasUsableRatingAnswer(SurveyAnswer answer) {
        return answer.isValid() && resolveRatingValue(answer) != null;
    }

    private boolean hasUsableNumberAnswer(SurveyAnswer answer) {
        return answer.isValid() && resolveNumericValue(answer) != null;
    }

    private String resolveSpecialAnswerCode(SurveyAnswer answer) {
        if (answer.getAnswerJson() == null || answer.getAnswerJson().isBlank()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(answer.getAnswerJson());
            JsonNode specialAnswerNode = root.get("specialAnswerCode");
            if (specialAnswerNode == null || specialAnswerNode.isNull()) {
                return null;
            }
            String value = trimToNull(specialAnswerNode.asText(null));
            return value == null ? null : normalize(value);
        } catch (Exception error) {
            return null;
        }
    }

    private BigDecimal resolveRatingValue(SurveyAnswer answer) {
        if (answer.getAnswerNumber() != null) {
            return answer.getAnswerNumber();
        }

        if (answer.getAnswerJson() == null || answer.getAnswerJson().isBlank()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(answer.getAnswerJson());
            JsonNode normalizedNumber = root.get("normalizedNumber");
            if (normalizedNumber == null || normalizedNumber.isNull()) {
                return null;
            }
            if (normalizedNumber.isNumber()) {
                return normalizedNumber.decimalValue();
            }
            String rawValue = normalizedNumber.asText(null);
            return rawValue == null || rawValue.isBlank() ? null : new BigDecimal(rawValue.trim());
        } catch (Exception error) {
            return null;
        }
    }

    private BigDecimal resolveNumericValue(SurveyAnswer answer) {
        return resolveRatingValue(answer);
    }

    private String resolveOpenEndedText(SurveyAnswer answer) {
        if (answer.getAnswerText() != null && !answer.getAnswerText().isBlank()) {
            return answer.getAnswerText().trim();
        }
        if (answer.getRawInputText() != null && !answer.getRawInputText().isBlank()) {
            return answer.getRawInputText().trim();
        }
        return extractNormalizedText(answer.getAnswerJson());
    }

    private String resolveRawOpenEndedText(SurveyAnswer answer) {
        if (answer.getRawInputText() != null && !answer.getRawInputText().isBlank()) {
            return answer.getRawInputText().trim();
        }
        if (answer.getAnswerJson() == null || answer.getAnswerJson().isBlank()) {
            return resolveOpenEndedText(answer);
        }
        try {
            JsonNode root = objectMapper.readTree(answer.getAnswerJson());
            String originalRawText = trimToNull(root.path("originalRawText").asText(null));
            if (originalRawText != null) {
                return originalRawText;
            }
            String rawText = trimToNull(root.path("rawText").asText(null));
            if (rawText != null) {
                return rawText;
            }
        } catch (Exception ignored) {
        }
        return resolveOpenEndedText(answer);
    }

    private String extractNormalizedText(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode normalizedText = root.get("normalizedText");
            if (normalizedText == null || normalizedText.isNull()) {
                return null;
            }
            String value = normalizedText.asText(null);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception error) {
            return null;
        }
    }

    private boolean isUsableChoiceAnswer(SurveyAnswer answer, List<String> labels) {
        if (labels.isEmpty()) {
            return false;
        }
        return answer.isValid()
                || answer.getSelectedOption() != null
                || hasReadableChoiceFallback(answer)
                || hasStructuredChoiceFallback(answer.getAnswerJson());
    }

    private boolean hasReadableChoiceFallback(SurveyAnswer answer) {
        return java.util.stream.Stream.of(answer.getAnswerText(), answer.getRawInputText())
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(value -> !value.isBlank());
    }

    private boolean hasStructuredChoiceFallback(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }

        try {
            JsonNode root = objectMapper.readTree(rawJson);
            return hasNonBlankJsonText(root, "normalizedText")
                    || hasNonBlankJsonText(root, "value")
                    || hasNonBlankJsonText(root, "selectedOptionId")
                    || hasNonEmptyJsonArray(root, "normalizedValues")
                    || hasNonEmptyJsonArray(root, "selectedOptionIds")
                    || hasNonEmptyJsonArray(root, "selectedOptions");
        } catch (Exception error) {
            return false;
        }
    }

    private boolean hasNonBlankJsonText(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        return node != null && !node.isNull() && !node.asText("").isBlank();
    }

    private boolean hasNonEmptyJsonArray(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        return node != null && node.isArray() && node.size() > 0;
    }

    private record ResolvedChoiceAnswer(SurveyAnswer answer, List<String> labels) {
    }

    private List<OperationAnalyticsBreakdownItemDto> toBreakdown(Map<String, Long> counts, long total) {
        return counts.entrySet().stream()
                .map(entry -> new OperationAnalyticsBreakdownItemDto(
                        slugify(entry.getKey()),
                        entry.getKey(),
                        entry.getValue(),
                        percentage(entry.getValue(), total)
                ))
                .toList();
    }

    private boolean isBinaryQuestion(List<SurveyQuestionOption> options) {
        if (options.size() != 2) {
            return false;
        }

        return options.stream()
                .map(option -> (option.getLabel() + " " + option.getValue()).toLowerCase(Locale.ROOT))
                .allMatch(value -> value.contains("yes")
                        || value.contains("no")
                        || value.contains("evet")
                        || value.contains("hayir")
                        || value.contains("true")
                        || value.contains("false"));
    }

    private List<OperationAnalyticsInsightItemDto> buildInsightItems(
            long totalContacts,
            long totalCallsAttempted,
            long completedResponses,
            long partialResponses,
            long failedCallJobs,
            double responseRate,
            double contactReachRate,
            double averageCompletionPercent,
            List<OperationAnalyticsQuestionSummaryDto> questionSummaries
    ) {
        List<OperationAnalyticsInsightItemDto> items = new ArrayList<>();

        if (totalContacts == 0) {
            items.add(new OperationAnalyticsInsightItemDto(
                    "awaiting-contacts",
                    "Analitik icin kisi bekleniyor",
                    "Bu operasyona kisi eklendiginde cagri ve yanit analitigi burada gercek veriden uretilir.",
                    "neutral"
            ));
            return items;
        }

        items.add(new OperationAnalyticsInsightItemDto(
                "reach",
                "Temas kapsami",
                totalCallsAttempted > 0
                        ? totalCallsAttempted + " kisiye cagri denemesi ulasildi. Temas orani %" + contactReachRate + "."
                        : "Henuz cagri denemesi yok. Operasyon basladiginda temas orani olusacak.",
                totalCallsAttempted > 0 ? "positive" : "neutral"
        ));

        OperationAnalyticsQuestionSummaryDto topChoiceQuestion = questionSummaries.stream()
                .filter(summary -> !"OPEN_ENDED".equals(summary.chartKind()))
                .filter(summary -> summary.breakdown().stream().anyMatch(item -> item.count() > 0))
                .findFirst()
                .orElse(null);
        if (topChoiceQuestion != null) {
            OperationAnalyticsBreakdownItemDto topChoice = topChoiceQuestion.breakdown().stream()
                    .max(Comparator.comparingLong(OperationAnalyticsBreakdownItemDto::count))
                    .orElse(null);
            if (topChoice != null && topChoice.count() > 0) {
                items.add(new OperationAnalyticsInsightItemDto(
                        "top-choice",
                        "En guclu cevap sinyali",
                        "\"" + topChoiceQuestion.questionTitle() + "\" sorusunda en cok secilen cevap "
                                + topChoice.label() + " oldu (%" + topChoice.percentage() + ").",
                        "positive"
                ));
            }
        }

        OperationAnalyticsQuestionSummaryDto ratingQuestion = questionSummaries.stream()
                .filter(summary -> "RATING".equals(summary.chartKind()))
                .filter(summary -> summary.averageRating() != null)
                .max(Comparator.comparingDouble(OperationAnalyticsQuestionSummaryDto::averageRating))
                .orElse(null);
        if (ratingQuestion != null) {
            items.add(new OperationAnalyticsInsightItemDto(
                    "rating",
                    "Ortalama puan",
                    "\"" + ratingQuestion.questionTitle() + "\" icin ortalama puan " + ratingQuestion.averageRating() + ".",
                    ratingQuestion.averageRating() >= 4 ? "positive" : "warning"
            ));
        }

        if (failedCallJobs > 0 || averageCompletionPercent < 60 || (completedResponses == 0 && partialResponses > 0)) {
            items.add(new OperationAnalyticsInsightItemDto(
                    "attention",
                    "Takip gerektiren sinyal",
                    failedCallJobs > 0
                            ? failedCallJobs + " cagri isi basarisiz sonuclandi. Eksik kalan veri ve cagri nedenleri incelenmeli."
                            : "Yanitlarin ortalama tamamlama orani %" + averageCompletionPercent
                                    + ". Kismi gorusmeler soru tamamlama kalitesini dusuruyor.",
                    "warning"
            ));
        } else if (responseRate < 35) {
            items.add(new OperationAnalyticsInsightItemDto(
                    "response-rate",
                    "Cevap orani izlenmeli",
                    "Cevap orani %" + responseRate + " seviyesinde. Daha fazla tamamlanan gorusme icin akis izlenmeli.",
                    "warning"
            ));
        }

        return items.stream().limit(4).toList();
    }

    private double percentage(long part, long total) {
        if (total <= 0) {
            return 0;
        }
        return round((part * 100.0) / total);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private String buildInsightSummary(
            Operation operation,
            long totalContacts,
            long completedResponses,
            long partialResponses,
            long failedCallJobs,
            double responseRate
    ) {
        if (totalContacts == 0) {
            return "Bu operasyon icin henuz kisi yuklenmedi. Analitik, yurutme oncesi hazirlik asamasinda bekliyor.";
        }

        if (completedResponses == 0 && partialResponses == 0) {
            return operation.getStatus() == OperationStatus.READY
                    ? "Tum on kosullar tamam. Yurutme basladiginda cevap ve soru dagilimlari burada canli olarak gorunur."
                    : "Henuz geri donen gorusme yaniti yok. Yurutme basladiktan sonra operasyonel sonuclar bu alana akar.";
        }

        if (operation.getStatus() == OperationStatus.FAILED) {
            return "Operasyon durmus olsa da toplanan kismi veri korunuyor. En kritik sonraki adim, basarisiz isleri inceleyip yeniden hazirlama karari vermek.";
        }

        if (operation.getStatus() == OperationStatus.RUNNING) {
            return "Canli veri akisi devam ediyor. Su anki tablo, cevap oraninin "
                    + String.format(Locale.US, "%.1f", responseRate)
                    + "% seviyesine geldigini ve gorusmeler tamamlandikca dagilimlarin guncellendigini gosteriyor.";
        }

        if (failedCallJobs > 0) {
            return "Operasyon kapandi. Tamamlanan cevaplara ek olarak inceleme gerektiren basarisiz cagri isleri de mevcut.";
        }

        return "Operasyon tamamlandi. Bu sayfa, sonuc ozetini ve soru bazli dagilimlari tek yuzeyde toplar.";
    }

    private String trimSample(String value) {
        if (value.length() <= 140) {
            return value;
        }
        return value.substring(0, 137).trim() + "...";
    }

    private String text(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            if (node.hasNonNull(field)) {
                return node.get(field).asText();
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeOrNull(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String normalized = normalize(trimmed);
        return normalized.isBlank() ? null : normalized;
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
                .replace('Ç', 'c')
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

        return Normalizer.normalize(normalized, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String slugify(String input) {
        return input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private String buildStateBlockingReason(OperationStatus status) {
        return switch (status) {
            case RUNNING -> "Operasyon zaten yurutmede.";
            case COMPLETED -> "Tamamlanmis operasyon yeniden baslatilamaz.";
            case FAILED -> "Basarisiz operasyon once incelenmeli.";
            case PAUSED -> "Duraklatilmis operasyon devam ettirilmeden yeniden baslatilamaz.";
            case CANCELLED -> "Iptal edilmis operasyon yeniden baslatilamaz.";
            default -> "Operasyonun mevcut durumu baslatmaya uygun degil.";
        };
    }

    private record QuestionGroupingMetadata(
            String groupCode,
            String groupTitle,
            String rowKey,
            String rowLabel,
            String optionSetCode
    ) {
    }

    private record OpenEndedGroupingResolution(
            String confirmedThemeCode,
            List<String> suggestedThemeCodes,
            String reviewReason
    ) {
    }
}


