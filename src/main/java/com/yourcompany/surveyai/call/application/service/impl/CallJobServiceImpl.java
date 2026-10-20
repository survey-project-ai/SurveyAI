package com.yourcompany.surveyai.call.application.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.call.application.dto.request.UpdateCallJobSurveyResponseAnswerRequest;
import com.yourcompany.surveyai.call.application.dto.request.UpdateCallJobSurveyResponseRequest;
import com.yourcompany.surveyai.call.application.dto.response.CallJobSurveyResponseAnswerDto;
import com.yourcompany.surveyai.call.application.dto.response.CallJobSurveyResponseAnswerOptionDto;
import com.yourcompany.surveyai.call.application.dto.response.CallJobSurveyResponseDto;
import com.yourcompany.surveyai.call.application.dto.response.CallJobListStatusDto;
import com.yourcompany.surveyai.call.application.dto.response.CallJobAttemptResponseDto;
import com.yourcompany.surveyai.call.application.dto.response.CallJobDetailResponseDto;
import com.yourcompany.surveyai.call.application.dto.response.CallJobPageResponseDto;
import com.yourcompany.surveyai.call.application.dto.response.CallJobResponseDto;
import com.yourcompany.surveyai.call.application.dto.response.CallJobSurveyResponseSummaryDto;
import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.call.application.service.CallJobService;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.operation.support.OperationContactPhoneResolver;
import com.yourcompany.surveyai.response.domain.entity.SurveyAnswer;
import com.yourcompany.surveyai.response.domain.entity.SurveyResponse;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import com.yourcompany.surveyai.response.repository.SurveyAnswerRepository;
import com.yourcompany.surveyai.response.repository.SurveyResponseRepository;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionOptionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CallJobServiceImpl implements CallJobService {

    private static final AtomicLong REDIAL_SEQUENCE = new AtomicLong();
    // Calls requested by an operator jump ahead of the regular queue (priority 5).
    private static final short MANUAL_CALL_PRIORITY = 10;

    private static final Set<CallJobStatus> QUEUED_STATUSES = EnumSet.of(
            CallJobStatus.PENDING,
            CallJobStatus.QUEUED,
            CallJobStatus.RETRY
    );

    private static final Set<CallJobStatus> FAILED_STATUSES = EnumSet.of(
            CallJobStatus.FAILED,
            CallJobStatus.DEAD_LETTER
    );

    private static final Set<CallAttemptStatus> ACTIVE_ATTEMPT_STATUSES = EnumSet.of(
            CallAttemptStatus.INITIATED,
            CallAttemptStatus.RINGING,
            CallAttemptStatus.IN_PROGRESS
    );

    private final CallJobRepository callJobRepository;
    private final CallAttemptRepository callAttemptRepository;
    private final OperationContactRepository operationContactRepository;
    private final OperationRepository operationRepository;
    private final SurveyResponseRepository surveyResponseRepository;
    private final SurveyAnswerRepository surveyAnswerRepository;
    private final SurveyQuestionRepository surveyQuestionRepository;
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository;
    private final CallJobDispatcher callJobDispatcher;
    private final ObjectMapper objectMapper;

    public CallJobServiceImpl(
            CallJobRepository callJobRepository,
            CallAttemptRepository callAttemptRepository,
            OperationContactRepository operationContactRepository,
            OperationRepository operationRepository,
            SurveyResponseRepository surveyResponseRepository,
            SurveyAnswerRepository surveyAnswerRepository,
            SurveyQuestionRepository surveyQuestionRepository,
            SurveyQuestionOptionRepository surveyQuestionOptionRepository,
            CallJobDispatcher callJobDispatcher,
            ObjectMapper objectMapper
    ) {
        this.callJobRepository = callJobRepository;
        this.callAttemptRepository = callAttemptRepository;
        this.operationContactRepository = operationContactRepository;
        this.operationRepository = operationRepository;
        this.surveyResponseRepository = surveyResponseRepository;
        this.surveyAnswerRepository = surveyAnswerRepository;
        this.surveyQuestionRepository = surveyQuestionRepository;
        this.surveyQuestionOptionRepository = surveyQuestionOptionRepository;
        this.callJobDispatcher = callJobDispatcher;
        this.objectMapper = objectMapper;
    }

    @Override
    public CallJobPageResponseDto listOperationCallJobs(
            UUID companyId,
            UUID operationId,
            int page,
            int size,
            String query,
            List<CallJobListStatusDto> statuses,
            String sortBy,
            String direction
    ) {
        ensureOperationExists(companyId, operationId);

        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim().toLowerCase();
        String normalizedSortBy = "updatedAt".equalsIgnoreCase(sortBy) ? "updatedAt" : "createdAt";
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;

        var pageResult = callJobRepository.findAll(
                buildSpecification(operationId, companyId, normalizedQuery, statuses),
                PageRequest.of(normalizedPage, normalizedSize, Sort.by(sortDirection, normalizedSortBy))
        );

        return new CallJobPageResponseDto(
                pageResult.getContent().stream().map(this::toDto).toList(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages(),
                pageResult.getNumber(),
                pageResult.getSize()
        );
    }

    @Override
    public List<CallJobResponseDto> listRecentlyCalledJobs(UUID companyId, int limit) {
        return callJobRepository.findRecentlyCalledForCompany(companyId, PageRequest.of(0, Math.min(Math.max(limit, 1), 50)))
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public CallJobDetailResponseDto getOperationCallJobDetail(UUID companyId, UUID operationId, UUID callJobId) {
        ensureOperationExists(companyId, operationId);
        CallJob callJob = getCallJob(companyId, operationId, callJobId);
        return buildDetailDto(callJob);
    }

    @Override
    @Transactional
    public CallJobDetailResponseDto retryOperationCallJob(UUID companyId, UUID operationId, UUID callJobId) {
        ensureOperationExists(companyId, operationId);
        CallJob callJob = getCallJob(companyId, operationId, callJobId);
        OperationContact contact = callJob.getOperationContact();
        CallAttempt latestAttempt = callAttemptRepository
                .findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(callJob.getId())
                .orElse(null);

        validateRetryEligibility(callJob, latestAttempt);
        callJobDispatcher.validateActiveProviderConfiguration();

        callJob.setStatus(CallJobStatus.RETRY);
        callJob.setAvailableAt(OffsetDateTime.now());
        callJob.setPriority(MANUAL_CALL_PRIORITY);
        callJob.setLockedAt(null);
        callJob.setLockedBy(null);
        callJobRepository.save(callJob);

        contact.setStatus(OperationContactStatus.RETRY);
        contact.setRetryCount((contact.getRetryCount() == null ? 0 : contact.getRetryCount()) + 1);
        contact.setNextRetryAt(null);
        operationContactRepository.save(contact);

        // The queue places the call after this transaction commits, within calling hours and the
        // operation's concurrency limit; in a paused operation it waits until the operation resumes.
        callJobDispatcher.requestDispatch(operationId);

        return buildDetailDto(getCallJob(companyId, operationId, callJobId));
    }

    @Override
    @Transactional
    public CallJobDetailResponseDto redialOperationCallJob(UUID companyId, UUID operationId, UUID callJobId) {
        ensureOperationExists(companyId, operationId);
        CallJob sourceJob = getCallJob(companyId, operationId, callJobId);
        CallAttempt latestAttempt = callAttemptRepository
                .findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(sourceJob.getId())
                .orElse(null);

        validateRedialEligibility(sourceJob, latestAttempt);
        callJobDispatcher.validateActiveProviderConfiguration();

        OperationContact contact = sourceJob.getOperationContact();
        OffsetDateTime now = OffsetDateTime.now();

        CallJob redialJob = new CallJob();
        redialJob.setCompany(sourceJob.getCompany());
        redialJob.setOperation(sourceJob.getOperation());
        redialJob.setOperationContact(contact);
        redialJob.setStatus(CallJobStatus.PENDING);
        redialJob.setPriority(MANUAL_CALL_PRIORITY);
        redialJob.setScheduledFor(now);
        redialJob.setAvailableAt(now);
        redialJob.setAttemptCount(0);
        redialJob.setMaxAttempts(sourceJob.getMaxAttempts() != null ? sourceJob.getMaxAttempts() : 3);
        redialJob.setIdempotencyKey(buildRedialIdempotencyKey(sourceJob, now));
        redialJob.setLastErrorCode(null);
        redialJob.setLastErrorMessage(null);
        redialJob.setLockedAt(null);
        redialJob.setLockedBy(null);
        redialJob = callJobRepository.save(redialJob);

        contact.setStatus(OperationContactStatus.RETRY);
        contact.setRetryCount((contact.getRetryCount() == null ? 0 : contact.getRetryCount()) + 1);
        contact.setNextRetryAt(null);
        operationContactRepository.save(contact);

        callJobDispatcher.requestDispatch(operationId);

        return buildDetailDto(getCallJob(companyId, operationId, redialJob.getId()));
    }

    @Override
    @Transactional
    public CallJobDetailResponseDto updateOperationCallJobSurveyResponse(
            UUID companyId,
            UUID operationId,
            UUID callJobId,
            UpdateCallJobSurveyResponseRequest request
    ) {
        ensureOperationExists(companyId, operationId);
        CallJob callJob = getCallJob(companyId, operationId, callJobId);
        List<CallAttempt> attempts = loadAttempts(callJob);
        Map<UUID, SurveyResponse> responsesByAttemptId = loadResponsesByAttemptId(attempts);
        SurveyResponse surveyResponse = resolvePrimarySurveyResponse(attempts, responsesByAttemptId);

        if (surveyResponse == null) {
            throw new ValidationException("Bu çağrı işinin düzenlenecek bir anket yanıtı yok.");
        }

        List<SurveyQuestion> questions = loadSurveyQuestions(callJob);
        if (questions.isEmpty()) {
            throw new ValidationException("Ankette düzenlenecek soru yok.");
        }

        Map<UUID, SurveyQuestion> questionsById = questions.stream()
                .collect(java.util.stream.Collectors.toMap(SurveyQuestion::getId, question -> question, (left, right) -> left, LinkedHashMap::new));
        Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId = loadOptionsByQuestionId(questions);
        Map<UUID, SurveyAnswer> answersByQuestionId = surveyAnswerRepository
                .findAllBySurveyResponse_IdAndDeletedAtIsNull(surveyResponse.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        answer -> answer.getSurveyQuestion().getId(),
                        answer -> answer,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        for (UpdateCallJobSurveyResponseAnswerRequest answerRequest : request.getAnswers()) {
            if (answerRequest == null || answerRequest.getQuestionId() == null) {
                throw new ValidationException("Düzenlenen her cevap bir anket sorusuna bağlı olmalıdır.");
            }

            SurveyQuestion question = questionsById.get(answerRequest.getQuestionId());
            if (question == null) {
                throw new ValidationException("Soru bu çağrı işinin anketine ait değil: " + answerRequest.getQuestionId());
            }

            List<SurveyQuestionOption> questionOptions = optionsByQuestionId.getOrDefault(question.getId(), List.of());
            SurveyAnswer existingAnswer = answersByQuestionId.get(question.getId());
            // The editor sends every question. Rewriting an untouched answer would mark it as a manual
            // edit, drop its AI theme coding and turn a valid special answer into an invalid one.
            if (!changesAnswer(question, existingAnswer, questionOptions, answerRequest)) {
                continue;
            }

            SurveyAnswer answer = existingAnswer != null ? existingAnswer : createManualAnswer(companyId, surveyResponse, question);
            answersByQuestionId.put(question.getId(), answer);
            applyManualAnswer(answer, question, questionOptions, answerRequest);
            surveyAnswerRepository.save(answer);
        }

        List<SurveyAnswer> persistedAnswers = surveyAnswerRepository.findAllBySurveyResponse_IdAndDeletedAtIsNull(surveyResponse.getId());
        int validAnswerCount = (int) persistedAnswers.stream().filter(SurveyAnswer::isValid).count();

        surveyResponse.setCompletionPercent(calculateCompletionPercent(questions.size(), validAnswerCount));
        surveyResponse.setStatus(resolveSurveyResponseStatus(questions.size(), validAnswerCount));
        if (validAnswerCount > 0 && surveyResponse.getCompletedAt() == null) {
            surveyResponse.setCompletedAt(OffsetDateTime.now());
        }
        surveyResponseRepository.save(surveyResponse);

        return buildDetailDto(getCallJob(companyId, operationId, callJobId));
    }

    private void ensureOperationExists(UUID companyId, UUID operationId) {
        operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId)
                .orElseThrow(() -> new NotFoundException("Bu şirkette operasyon bulunamadı: " + operationId));
    }

    private CallJob getCallJob(UUID companyId, UUID operationId, UUID callJobId) {
        return callJobRepository.findByIdAndOperation_IdAndCompany_IdAndDeletedAtIsNull(callJobId, operationId, companyId)
                .orElseThrow(() -> new NotFoundException("Bu operasyonda çağrı işi bulunamadı: " + callJobId));
    }

    private Specification<CallJob> buildSpecification(
            UUID operationId,
            UUID companyId,
            String query,
            List<CallJobListStatusDto> statuses
    ) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("operation").get("id"), operationId));
            predicates.add(criteriaBuilder.equal(root.get("company").get("id"), companyId));
            predicates.add(criteriaBuilder.isNull(root.get("deletedAt")));

            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(mapFilterStatuses(statuses)));
            }

            if (query != null) {
                var contact = root.join("operationContact", jakarta.persistence.criteria.JoinType.LEFT);
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(contact.get("firstName"), "")), "%" + query + "%"),
                        criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(contact.get("lastName"), "")), "%" + query + "%"),
                        criteriaBuilder.like(criteriaBuilder.lower(
                                criteriaBuilder.concat(
                                        criteriaBuilder.concat(criteriaBuilder.coalesce(contact.get("firstName"), ""), " "),
                                        criteriaBuilder.coalesce(contact.get("lastName"), "")
                                )
                        ), "%" + query + "%"),
                        criteriaBuilder.like(contact.get("phoneNumber"), "%" + query.replaceAll("\\s+", "") + "%")
                ));
            }

            if (criteriaQuery != null) {
                criteriaQuery.distinct(true);
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Set<CallJobStatus> mapFilterStatuses(List<CallJobListStatusDto> statuses) {
        EnumSet<CallJobStatus> mappedStatuses = EnumSet.noneOf(CallJobStatus.class);
        for (CallJobListStatusDto status : statuses) {
            switch (status) {
                case QUEUED -> mappedStatuses.addAll(QUEUED_STATUSES);
                case IN_PROGRESS -> mappedStatuses.add(CallJobStatus.IN_PROGRESS);
                case COMPLETED -> mappedStatuses.add(CallJobStatus.COMPLETED);
                case FAILED -> mappedStatuses.addAll(FAILED_STATUSES);
                case SKIPPED -> mappedStatuses.add(CallJobStatus.CANCELLED);
            }
        }
        return mappedStatuses;
    }

    private CallJobResponseDto toDto(CallJob callJob) {
        OperationContact contact = callJob.getOperationContact();
        String personName = buildPersonName(contact);
        List<CallAttempt> attempts = loadAttempts(callJob);
        CallAttempt latestAttempt = attempts.isEmpty() ? null : attempts.get(0);
        int answerCount = resolveAnswerCount(attempts);

        return new CallJobResponseDto(
                callJob.getId(),
                callJob.getCompany().getId(),
                callJob.getOperation().getId(),
                contact.getId(),
                personName,
                OperationContactPhoneResolver.resolveDisplayPhoneNumber(contact),
                mapListStatus(callJob.getStatus()),
                callJob.getStatus(),
                callJob.getAttemptCount() == null ? 0 : callJob.getAttemptCount(),
                callJob.getMaxAttempts() == null ? 0 : callJob.getMaxAttempts(),
                callJob.getLastErrorCode(),
                callJob.getLastErrorMessage(),
                answerCount,
                buildLastResultSummary(callJob, latestAttempt),
                callJob.getCreatedAt(),
                callJob.getUpdatedAt()
        );
    }

    private List<CallAttempt> loadAttempts(CallJob callJob) {
        return callAttemptRepository.findAllByCallJob_IdOrderByCreatedAtDesc(callJob.getId()).stream()
                .filter(attempt -> attempt.getDeletedAt() == null)
                .toList();
    }

    private int resolveAnswerCount(CallJob callJob) {
        return resolveAnswerCount(loadAttempts(callJob));
    }

    private int resolveAnswerCount(List<CallAttempt> attempts) {
        if (attempts.isEmpty()) {
            return 0;
        }

        Map<UUID, SurveyResponse> responsesByAttemptId = loadResponsesByAttemptId(attempts);
        Map<UUID, List<SurveyAnswer>> answersByResponseId = loadAnswersByResponseId(responsesByAttemptId.values());

        return attempts.stream()
                .map(attempt -> toSurveyResponseSummary(responsesByAttemptId.get(attempt.getId()), answersByResponseId))
                .filter(Objects::nonNull)
                .findFirst()
                .map(summary -> summary.validAnswerCount() > 0 ? summary.validAnswerCount() : summary.answerCount())
                .orElse(0);
    }

    private CallJobDetailResponseDto buildDetailDto(CallJob callJob) {
        List<CallAttempt> attempts = loadAttempts(callJob);
        Map<UUID, SurveyResponse> responsesByAttemptId = loadResponsesByAttemptId(attempts);
        Map<UUID, List<SurveyAnswer>> answersByResponseId = loadAnswersByResponseId(responsesByAttemptId.values());
        List<SurveyQuestion> questions = loadSurveyQuestions(callJob);
        Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId = loadOptionsByQuestionId(questions);

        CallAttempt latestAttempt = attempts.isEmpty() ? null : attempts.get(0);
        CallJobSurveyResponseSummaryDto relatedResponse = attempts.stream()
                .map(attempt -> toSurveyResponseSummary(responsesByAttemptId.get(attempt.getId()), answersByResponseId))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        SurveyResponse relatedSurveyResponse = resolvePrimarySurveyResponse(attempts, responsesByAttemptId);
        CallJobSurveyResponseDto relatedResponseDetail = toSurveyResponseDetail(
                relatedSurveyResponse,
                answersByResponseId,
                questions,
                optionsByQuestionId
        );

        String transcriptSummary = relatedResponse != null && relatedResponse.aiSummaryText() != null && !relatedResponse.aiSummaryText().isBlank()
                ? relatedResponse.aiSummaryText().trim()
                : latestAttempt != null && latestAttempt.getFailureReason() != null && !latestAttempt.getFailureReason().isBlank()
                        ? latestAttempt.getFailureReason().trim()
                        : callJob.getLastErrorMessage();

        String transcriptText = relatedResponse != null && relatedResponse.transcriptText() != null && !relatedResponse.transcriptText().isBlank()
                ? relatedResponse.transcriptText()
                : null;

        boolean partialResponseDataExists = relatedResponse != null
                || attempts.stream().anyMatch(attempt -> hasPartialData(attempt, responsesByAttemptId.get(attempt.getId())));

        List<CallJobAttemptResponseDto> attemptDtos = attempts.stream()
                .map(attempt -> new CallJobAttemptResponseDto(
                        attempt.getId(),
                        attempt.getAttemptNumber(),
                        latestAttempt != null && Objects.equals(latestAttempt.getId(), attempt.getId()),
                        attempt.getProvider(),
                        attempt.getProviderCallId(),
                        attempt.getStatus(),
                        attempt.getDialedAt(),
                        attempt.getConnectedAt(),
                        attempt.getEndedAt(),
                        attempt.getDurationSeconds(),
                        attempt.getHangupReason(),
                        attempt.getFailureReason(),
                        attempt.getTranscriptStorageKey(),
                        toSurveyResponseSummary(responsesByAttemptId.get(attempt.getId()), answersByResponseId)
                ))
                .toList();
        int totalAttempts = Math.max(callJob.getAttemptCount() == null ? 0 : callJob.getAttemptCount(), attempts.size());

        return new CallJobDetailResponseDto(
                callJob.getId(),
                callJob.getCompany().getId(),
                callJob.getOperation().getId(),
                callJob.getOperation().getName(),
                callJob.getOperation().getSurvey().getId(),
                callJob.getOperation().getSurvey().getName(),
                callJob.getOperationContact().getId(),
                buildPersonName(callJob.getOperationContact()),
                OperationContactPhoneResolver.resolveDisplayPhoneNumber(callJob.getOperationContact()),
                mapListStatus(callJob.getStatus()),
                callJob.getStatus(),
                callJob.getScheduledFor(),
                callJob.getAvailableAt(),
                totalAttempts,
                callJob.getMaxAttempts() == null ? 0 : callJob.getMaxAttempts(),
                totalAttempts <= 1,
                totalAttempts > 1,
                latestAttempt == null ? null : latestAttempt.getProviderCallId(),
                latestAttempt == null ? null : latestAttempt.getTranscriptStorageKey(),
                callJob.getLastErrorCode(),
                callJob.getLastErrorMessage(),
                FAILED_STATUSES.contains(callJob.getStatus()),
                resolveFailureReason(callJob, latestAttempt),
                isRetryable(callJob, latestAttempt),
                isRedialable(callJob, latestAttempt),
                partialResponseDataExists,
                transcriptSummary,
                transcriptText,
                relatedResponseDetail,
                callJob.getCreatedAt(),
                callJob.getUpdatedAt(),
                attemptDtos
        );
    }

    private String buildPersonName(OperationContact contact) {
        String firstName = contact.getFirstName() == null ? "" : contact.getFirstName().trim();
        String lastName = contact.getLastName() == null ? "" : contact.getLastName().trim();
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isBlank() ? "Adsız kişi" : fullName;
    }

    private CallJobListStatusDto mapListStatus(CallJobStatus status) {
        return switch (status) {
            case PENDING, QUEUED, RETRY -> CallJobListStatusDto.QUEUED;
            case IN_PROGRESS -> CallJobListStatusDto.IN_PROGRESS;
            case COMPLETED -> CallJobListStatusDto.COMPLETED;
            case FAILED, DEAD_LETTER -> CallJobListStatusDto.FAILED;
            case CANCELLED -> CallJobListStatusDto.SKIPPED;
        };
    }

    private String buildLastResultSummary(CallJob callJob, CallAttempt latestAttempt) {
        if (latestAttempt != null) {
            switch (latestAttempt.getStatus()) {
                case BUSY:
                    return "Hat meşgul.";
                case NO_ANSWER:
                    return "Çağrı yanıtlanmadı.";
                case VOICEMAIL:
                    return "Çağrı sesli mesaja düştü.";
                case CANCELLED:
                    return "Çağrı iptal edildi.";
                default:
                    break;
            }
        }

        if (callJob.getLastErrorMessage() != null && !callJob.getLastErrorMessage().isBlank()) {
            return callJob.getLastErrorMessage().trim();
        }

        return switch (mapListStatus(callJob.getStatus())) {
            case QUEUED -> "İş sırada bekliyor.";
            case IN_PROGRESS -> "Çağrı yürütme adımı devam ediyor.";
            case COMPLETED -> "İş başarıyla tamamlandı.";
            case FAILED -> "Son deneme başarısız oldu.";
            case SKIPPED -> "İş işlenmeden kapatıldı.";
        };
    }

    private Map<UUID, SurveyResponse> loadResponsesByAttemptId(List<CallAttempt> attempts) {
        List<UUID> attemptIds = attempts.stream().map(CallAttempt::getId).toList();
        if (attemptIds.isEmpty()) {
            return Map.of();
        }

        return surveyResponseRepository.findAllByCallAttempt_IdInAndDeletedAtIsNull(attemptIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        response -> response.getCallAttempt().getId(),
                        response -> response,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Map<UUID, List<SurveyAnswer>> loadAnswersByResponseId(Collection<SurveyResponse> responses) {
        List<UUID> responseIds = responses.stream().map(SurveyResponse::getId).toList();
        if (responseIds.isEmpty()) {
            return Map.of();
        }

        return surveyAnswerRepository.findAllBySurveyResponse_IdInAndDeletedAtIsNull(responseIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        answer -> answer.getSurveyResponse().getId(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ));
    }

    private List<SurveyQuestion> loadSurveyQuestions(CallJob callJob) {
        return surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(
                callJob.getOperation().getSurvey().getId()
        );
    }

    private Map<UUID, List<SurveyQuestionOption>> loadOptionsByQuestionId(List<SurveyQuestion> questions) {
        List<UUID> questionIds = questions.stream().map(SurveyQuestion::getId).toList();
        if (questionIds.isEmpty()) {
            return Map.of();
        }

        return surveyQuestionOptionRepository
                .findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(questionIds)
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        option -> option.getSurveyQuestion().getId(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ));
    }

    private CallJobSurveyResponseSummaryDto toSurveyResponseSummary(
            SurveyResponse surveyResponse,
            Map<UUID, List<SurveyAnswer>> answersByResponseId
    ) {
        if (surveyResponse == null) {
            return null;
        }

        List<SurveyAnswer> answers = answersByResponseId.getOrDefault(surveyResponse.getId(), List.of());
        int validAnswerCount = (int) answers.stream().filter(SurveyAnswer::isValid).count();

        return new CallJobSurveyResponseSummaryDto(
                surveyResponse.getId(),
                surveyResponse.getStatus(),
                surveyResponse.getCompletionPercent(),
                answers.size(),
                validAnswerCount,
                isUsableResponse(surveyResponse, validAnswerCount),
                surveyResponse.getStartedAt(),
                surveyResponse.getCompletedAt(),
                surveyResponse.getAiSummaryText(),
                surveyResponse.getTranscriptText()
        );
    }

    private CallJobSurveyResponseDto toSurveyResponseDetail(
            SurveyResponse surveyResponse,
            Map<UUID, List<SurveyAnswer>> answersByResponseId,
            List<SurveyQuestion> questions,
            Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId
    ) {
        if (surveyResponse == null) {
            return null;
        }

        List<SurveyAnswer> answers = answersByResponseId.getOrDefault(surveyResponse.getId(), List.of());
        int validAnswerCount = (int) answers.stream().filter(SurveyAnswer::isValid).count();
        Map<UUID, SurveyAnswer> answersByQuestionId = answers.stream()
                .collect(java.util.stream.Collectors.toMap(
                        answer -> answer.getSurveyQuestion().getId(),
                        answer -> answer,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        List<CallJobSurveyResponseAnswerDto> answerDtos = questions.stream()
                .map(question -> toAnswerDetail(question, answersByQuestionId.get(question.getId()), optionsByQuestionId.getOrDefault(question.getId(), List.of())))
                .toList();

        return new CallJobSurveyResponseDto(
                surveyResponse.getId(),
                surveyResponse.getStatus(),
                surveyResponse.getCompletionPercent(),
                answers.size(),
                validAnswerCount,
                isUsableResponse(surveyResponse, validAnswerCount),
                surveyResponse.getStartedAt(),
                surveyResponse.getCompletedAt(),
                surveyResponse.getAiSummaryText(),
                surveyResponse.getTranscriptText(),
                answerDtos
        );
    }

    private CallJobSurveyResponseAnswerDto toAnswerDetail(
            SurveyQuestion question,
            SurveyAnswer answer,
            List<SurveyQuestionOption> options
    ) {
        List<UUID> selectedOptionIds = resolveSelectedOptionIds(question, answer, options);
        UUID selectedOptionId = answer != null && answer.getSelectedOption() != null
                ? answer.getSelectedOption().getId()
                : selectedOptionIds.size() == 1 ? selectedOptionIds.get(0) : null;

        return new CallJobSurveyResponseAnswerDto(
                answer == null ? null : answer.getId(),
                question.getId(),
                question.getCode(),
                question.getQuestionOrder(),
                question.getTitle(),
                question.getQuestionType(),
                question.isRequired(),
                answer == null ? null : answer.getAnswerText(),
                answer == null ? null : answer.getAnswerNumber(),
                selectedOptionId,
                selectedOptionIds,
                answer != null && answer.isValid(),
                answer == null ? null : answer.getInvalidReason(),
                answer == null ? null : answer.getRawInputText(),
                resolveDisplayValue(question, answer, options, selectedOptionIds),
                isManuallyEdited(answer),
                options.stream()
                        .map(option -> new CallJobSurveyResponseAnswerOptionDto(
                                option.getId(),
                                option.getOptionCode(),
                                option.getLabel(),
                                option.getValue(),
                                option.getOptionOrder() == null ? 0 : option.getOptionOrder()
                        ))
                        .toList()
        );
    }

    private SurveyResponse resolvePrimarySurveyResponse(List<CallAttempt> attempts, Map<UUID, SurveyResponse> responsesByAttemptId) {
        return attempts.stream()
                .map(attempt -> responsesByAttemptId.get(attempt.getId()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean changesAnswer(
            SurveyQuestion question,
            SurveyAnswer existingAnswer,
            List<SurveyQuestionOption> options,
            UpdateCallJobSurveyResponseAnswerRequest request
    ) {
        if (request.getCodedThemes() != null) {
            return true;
        }

        // Compare against the values the editor received for this answer.
        CallJobSurveyResponseAnswerDto current = toAnswerDetail(question, existingAnswer, options);
        Set<UUID> requestedOptionIds = request.getSelectedOptionIds() == null
                ? Set.of()
                : request.getSelectedOptionIds().stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return !Objects.equals(trimToNull(current.answerText()), trimToNull(request.getAnswerText()))
                || !sameNumber(current.answerNumber(), request.getAnswerNumber())
                || !Objects.equals(current.selectedOptionId(), request.getSelectedOptionId())
                || !Set.copyOf(current.selectedOptionIds()).equals(requestedOptionIds);
    }

    private boolean sameNumber(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private SurveyAnswer createManualAnswer(UUID companyId, SurveyResponse surveyResponse, SurveyQuestion question) {
        SurveyAnswer answer = new SurveyAnswer();
        answer.setCompany(surveyResponse.getCompany());
        answer.setSurveyResponse(surveyResponse);
        answer.setSurveyQuestion(question);
        answer.setAnswerType(question.getQuestionType());
        answer.setRetryCount(0);
        answer.setConfidenceScore(BigDecimal.ONE);
        answer.setAnswerJson("{}");
        return answer;
    }

    private void applyManualAnswer(
            SurveyAnswer answer,
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            UpdateCallJobSurveyResponseAnswerRequest request
    ) {
        String originalRawText = extractOriginalRawText(answer);
        answer.setAnswerType(question.getQuestionType());
        answer.setConfidenceScore(BigDecimal.ONE);
        answer.setSelectedOption(null);
        answer.setAnswerText(null);
        answer.setAnswerNumber(null);
        answer.setInvalidReason(null);

        String trimmedText = trimToNull(request.getAnswerText());
        List<SurveyQuestionOption> matchedOptions = resolveMatchedOptions(question, options, request);

        switch (question.getQuestionType()) {
            case OPEN_ENDED -> {
                answer.setRawInputText(originalRawText != null ? originalRawText : trimmedText);
                answer.setAnswerText(trimmedText);
                answer.setValid(trimmedText != null);
                if (trimmedText == null) {
                    answer.setInvalidReason("Manuel olarak boş bırakıldı");
                }
            }
            case NUMBER -> {
                answer.setRawInputText(request.getAnswerNumber() == null ? trimmedText : request.getAnswerNumber().stripTrailingZeros().toPlainString());
                answer.setAnswerNumber(request.getAnswerNumber());
                answer.setAnswerText(request.getAnswerNumber() == null ? trimmedText : request.getAnswerNumber().stripTrailingZeros().toPlainString());
                answer.setValid(request.getAnswerNumber() != null);
                if (request.getAnswerNumber() == null) {
                    answer.setInvalidReason("Geçerli bir sayı girilmedi");
                }
            }
            case RATING -> {
                answer.setRawInputText(request.getAnswerNumber() == null ? trimmedText : request.getAnswerNumber().stripTrailingZeros().toPlainString());
                answer.setAnswerNumber(request.getAnswerNumber());
                answer.setAnswerText(request.getAnswerNumber() == null ? trimmedText : request.getAnswerNumber().stripTrailingZeros().toPlainString());
                answer.setValid(request.getAnswerNumber() != null);
                if (request.getAnswerNumber() == null) {
                    answer.setInvalidReason("Geçerli bir puan seçilmedi");
                }
            }
            case SINGLE_CHOICE -> {
                SurveyQuestionOption matched = matchedOptions.isEmpty() ? null : matchedOptions.getFirst();
                answer.setSelectedOption(matched);
                answer.setAnswerText(matched != null ? matched.getLabel() : trimmedText);
                answer.setRawInputText(matched != null ? matched.getLabel() : trimmedText);
                answer.setValid(matched != null);
                if (matched == null) {
                    answer.setInvalidReason("Geçerli bir seçenek seçilmedi");
                }
            }
            case MULTI_CHOICE -> {
                List<String> labels = matchedOptions.stream().map(SurveyQuestionOption::getLabel).toList();
                answer.setAnswerText(labels.isEmpty() ? trimmedText : String.join(", ", labels));
                answer.setRawInputText(labels.isEmpty() ? trimmedText : String.join(", ", labels));
                answer.setValid(!matchedOptions.isEmpty());
                if (matchedOptions.isEmpty()) {
                    answer.setInvalidReason("En az bir seçenek seçilmedi");
                }
            }
        }

        answer.setAnswerJson(buildManualAnswerJson(question, answer, matchedOptions, request, originalRawText));
    }

    private String buildManualAnswerJson(
            SurveyQuestion question,
            SurveyAnswer answer,
            List<SurveyQuestionOption> matchedOptions,
            UpdateCallJobSurveyResponseAnswerRequest request,
            String originalRawText
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // Themes left out of the request are kept; answer still holds the previous JSON at this point.
        List<String> codedThemes = question.getQuestionType() != QuestionType.OPEN_ENDED
                ? List.of()
                : request.getCodedThemes() != null
                        ? normalizeCodedThemes(request.getCodedThemes())
                        : extractCodedThemes(answer.getAnswerJson());
        payload.put("questionType", question.getQuestionType());
        payload.put("manualEdit", true);
        payload.put("updatedAt", OffsetDateTime.now().toString());
        payload.put("selectedOptionId", answer.getSelectedOption() != null ? answer.getSelectedOption().getId() : null);
        payload.put("selectedOptionIds", matchedOptions.stream().map(SurveyQuestionOption::getId).map(UUID::toString).toList());
        payload.put("normalizedValues", matchedOptions.stream().map(SurveyQuestionOption::getLabel).toList());
        payload.put("normalizedText", answer.getAnswerText());
        payload.put("normalizedNumber", answer.getAnswerNumber());
        payload.put("rawText", answer.getRawInputText());
        payload.put("originalRawText", originalRawText);
        payload.put("value", answer.getAnswerText());
        payload.put("codedThemes", codedThemes);
        payload.put("invalidReason", answer.getInvalidReason());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }

    private List<String> extractCodedThemes(String answerJson) {
        if (answerJson == null || answerJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode themesNode = objectMapper.readTree(answerJson).path("codedThemes");
            if (!themesNode.isArray()) {
                return List.of();
            }
            List<String> themes = new ArrayList<>();
            themesNode.forEach(node -> {
                String theme = trimToNull(node.asText(null));
                if (theme != null) {
                    themes.add(theme);
                }
            });
            return themes;
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private List<String> normalizeCodedThemes(List<String> codedThemes) {
        if (codedThemes == null) {
            return List.of();
        }
        return codedThemes.stream()
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private String extractOriginalRawText(SurveyAnswer answer) {
        if (answer.getAnswerJson() != null && !answer.getAnswerJson().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(answer.getAnswerJson());
                String originalRawText = trimToNull(root.path("originalRawText").asText(null));
                if (originalRawText != null) {
                    return originalRawText;
                }
            } catch (Exception ignored) {
            }
        }
        return trimToNull(answer.getRawInputText());
    }

    private List<SurveyQuestionOption> resolveMatchedOptions(
            SurveyQuestion question,
            List<SurveyQuestionOption> options,
            UpdateCallJobSurveyResponseAnswerRequest request
    ) {
        if (question.getQuestionType() == QuestionType.SINGLE_CHOICE) {
            if (request.getSelectedOptionId() == null) {
                return List.of();
            }
            return options.stream()
                    .filter(option -> Objects.equals(option.getId(), request.getSelectedOptionId()))
                    .findFirst()
                    .map(List::of)
                    .orElseThrow(() -> new ValidationException("Seçilen seçenek bu soruya ait değil: " + question.getId()));
        }

        if (question.getQuestionType() == QuestionType.MULTI_CHOICE) {
            List<UUID> selectedOptionIds = request.getSelectedOptionIds() == null ? List.of() : request.getSelectedOptionIds().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (selectedOptionIds.isEmpty()) {
                return List.of();
            }

            List<SurveyQuestionOption> matchedOptions = options.stream()
                    .filter(option -> selectedOptionIds.contains(option.getId()))
                    .toList();
            if (matchedOptions.size() != selectedOptionIds.size()) {
                throw new ValidationException("Seçilen seçeneklerden en az biri bu soruya ait değil: " + question.getId());
            }
            return matchedOptions;
        }

        return List.of();
    }

    private BigDecimal calculateCompletionPercent(int questionCount, int validAnswerCount) {
        if (questionCount <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf((validAnswerCount * 100.0) / questionCount).setScale(2, RoundingMode.HALF_UP);
    }

    private SurveyResponseStatus resolveSurveyResponseStatus(int questionCount, int validAnswerCount) {
        if (validAnswerCount <= 0) {
            return SurveyResponseStatus.INVALID;
        }
        if (questionCount > 0 && validAnswerCount >= questionCount) {
            return SurveyResponseStatus.COMPLETED;
        }
        return SurveyResponseStatus.PARTIAL;
    }

    private List<UUID> resolveSelectedOptionIds(
            SurveyQuestion question,
            SurveyAnswer answer,
            List<SurveyQuestionOption> options
    ) {
        if (answer == null) {
            return List.of();
        }

        if (answer.getSelectedOption() != null) {
            return List.of(answer.getSelectedOption().getId());
        }

        if (question.getQuestionType() == QuestionType.MULTI_CHOICE) {
            List<UUID> fromJson = parseSelectedOptionIds(answer.getAnswerJson(), options);
            if (!fromJson.isEmpty()) {
                return fromJson;
            }

            return matchLabelsToOptionIds(splitAnswerText(answer.getAnswerText()), options);
        }

        if (question.getQuestionType() == QuestionType.SINGLE_CHOICE) {
            return parseSelectedOptionIds(answer.getAnswerJson(), options);
        }

        return List.of();
    }

    private List<UUID> parseSelectedOptionIds(String answerJson, List<SurveyQuestionOption> options) {
        if (answerJson == null || answerJson.isBlank()) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(answerJson);
            List<UUID> ids = new ArrayList<>();
            if (root.has("selectedOptionId")) {
                UUID parsed = parseOptionId(root.get("selectedOptionId").asText(null), options);
                if (parsed != null) {
                    ids.add(parsed);
                }
            }
            if (root.has("selectedOptionIds") && root.get("selectedOptionIds").isArray()) {
                for (JsonNode node : root.get("selectedOptionIds")) {
                    UUID parsed = parseOptionId(node.asText(null), options);
                    if (parsed != null && !ids.contains(parsed)) {
                        ids.add(parsed);
                    }
                }
            }
            if (!ids.isEmpty()) {
                return ids;
            }
            if (root.has("normalizedValues") && root.get("normalizedValues").isArray()) {
                List<String> labels = new ArrayList<>();
                for (JsonNode node : root.get("normalizedValues")) {
                    if (node != null && !node.isNull()) {
                        labels.add(node.asText());
                    }
                }
                return matchLabelsToOptionIds(labels, options);
            }
            return List.of();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private UUID parseOptionId(String rawValue, List<SurveyQuestionOption> options) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            UUID candidate = UUID.fromString(rawValue);
            return options.stream().anyMatch(option -> Objects.equals(option.getId(), candidate)) ? candidate : null;
        } catch (IllegalArgumentException ignored) {
            return options.stream()
                    .filter(option -> optionMatches(option, rawValue))
                    .map(SurveyQuestionOption::getId)
                    .findFirst()
                    .orElse(null);
        }
    }

    private List<UUID> matchLabelsToOptionIds(List<String> labels, List<SurveyQuestionOption> options) {
        if (labels == null || labels.isEmpty()) {
            return List.of();
        }
        return labels.stream()
                .map(label -> options.stream()
                        .filter(option -> optionMatches(option, label))
                        .map(SurveyQuestionOption::getId)
                        .findFirst()
                        .orElse(null))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<String> splitAnswerText(String answerText) {
        if (answerText == null || answerText.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(answerText.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
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

    private String resolveDisplayValue(
            SurveyQuestion question,
            SurveyAnswer answer,
            List<SurveyQuestionOption> options,
            List<UUID> selectedOptionIds
    ) {
        if (answer == null) {
            return "Henüz cevap yok";
        }
        return switch (question.getQuestionType()) {
            case OPEN_ENDED -> trimToNull(answer.getAnswerText()) == null ? "Boş" : answer.getAnswerText().trim();
            case NUMBER -> answer.getAnswerNumber() == null ? "Boş" : answer.getAnswerNumber().stripTrailingZeros().toPlainString();
            case RATING -> answer.getAnswerNumber() == null ? "Boş" : answer.getAnswerNumber().stripTrailingZeros().toPlainString();
            case SINGLE_CHOICE, MULTI_CHOICE -> {
                if (!selectedOptionIds.isEmpty()) {
                    yield options.stream()
                            .filter(option -> selectedOptionIds.contains(option.getId()))
                            .map(SurveyQuestionOption::getLabel)
                            .reduce((left, right) -> left + ", " + right)
                            .orElse(answer.getAnswerText() == null ? "Boş" : answer.getAnswerText());
                }
                yield trimToNull(answer.getAnswerText()) == null ? "Boş" : answer.getAnswerText().trim();
            }
        };
    }

    private boolean isManuallyEdited(SurveyAnswer answer) {
        if (answer == null || answer.getAnswerJson() == null || answer.getAnswerJson().isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(answer.getAnswerJson());
            return root.path("manualEdit").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private boolean isUsableResponse(SurveyResponse surveyResponse, int validAnswerCount) {
        return validAnswerCount > 0 && EnumSet.of(SurveyResponseStatus.COMPLETED, SurveyResponseStatus.PARTIAL)
                .contains(surveyResponse.getStatus());
    }

    private boolean hasPartialData(CallAttempt attempt, SurveyResponse surveyResponse) {
        return surveyResponse != null
                || (attempt.getTranscriptStorageKey() != null && !attempt.getTranscriptStorageKey().isBlank())
                || (attempt.getProviderCallId() != null && !attempt.getProviderCallId().isBlank());
    }

    private String resolveFailureReason(CallJob callJob, CallAttempt latestAttempt) {
        if (latestAttempt != null && latestAttempt.getFailureReason() != null && !latestAttempt.getFailureReason().isBlank()) {
            return latestAttempt.getFailureReason().trim();
        }
        if (callJob.getLastErrorMessage() != null && !callJob.getLastErrorMessage().isBlank()) {
            return callJob.getLastErrorMessage().trim();
        }
        return null;
    }

    private void validateRetryEligibility(CallJob callJob, CallAttempt latestAttempt) {
        if (latestAttempt != null && ACTIVE_ATTEMPT_STATUSES.contains(latestAttempt.getStatus())) {
            throw new ValidationException("Bu çağrı işi için zaten devam eden bir arama denemesi var.");
        }
    }

    private void validateRedialEligibility(CallJob callJob, CallAttempt latestAttempt) {
        if (!isRedialable(callJob, latestAttempt)) {
            throw new ValidationException("Devam eden bir arama denemesi varken yeniden arama yapılamaz.");
        }
    }

    private boolean isRetryable(CallJob callJob, CallAttempt latestAttempt) {
        return latestAttempt == null || !ACTIVE_ATTEMPT_STATUSES.contains(latestAttempt.getStatus());
    }

    private boolean isRedialable(CallJob callJob, CallAttempt latestAttempt) {
        if (latestAttempt != null && ACTIVE_ATTEMPT_STATUSES.contains(latestAttempt.getStatus())) {
            return false;
        }
        return true;
    }

    private String buildRedialIdempotencyKey(CallJob sourceJob, OffsetDateTime now) {
        return sourceJob.getOperation().getId()
                + ":"
                + sourceJob.getOperationContact().getId()
                + ":redial:"
                + now.toInstant().toEpochMilli()
                + ":"
                + REDIAL_SEQUENCE.incrementAndGet();
    }
}
