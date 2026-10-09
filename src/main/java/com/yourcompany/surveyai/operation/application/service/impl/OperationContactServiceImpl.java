package com.yourcompany.surveyai.operation.application.service.impl;

import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.operation.application.dto.request.OperationContactInput;
import com.yourcompany.surveyai.operation.application.dto.request.UploadOperationContactsRequest;
import com.yourcompany.surveyai.operation.application.dto.response.OperationContactPageResponseDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationContactReadinessDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationContactResponseDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationContactStatusCountDto;
import com.yourcompany.surveyai.operation.application.dto.response.OperationContactSummaryResponseDto;
import com.yourcompany.surveyai.operation.application.service.OperationContactService;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.domain.enums.OperationStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.operation.support.OperationContactPhoneResolver;
import com.yourcompany.surveyai.operation.support.PhoneNumberNormalizer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OperationContactServiceImpl implements OperationContactService {

    private final OperationRepository operationRepository;
    private final OperationContactRepository operationContactRepository;
    private final CallJobRepository callJobRepository;
    private final CallJobDispatcher callJobDispatcher;
    private final Validator validator;
    private final boolean allowDuplicatePhoneNumbersForDev;

    public OperationContactServiceImpl(
            OperationRepository operationRepository,
            OperationContactRepository operationContactRepository,
            CallJobRepository callJobRepository,
            CallJobDispatcher callJobDispatcher,
            Validator validator,
            @Value("${surveyai.dev.allow-duplicate-operation-contact-phone-numbers:false}") boolean allowDuplicatePhoneNumbersForDev
    ) {
        this.operationRepository = operationRepository;
        this.operationContactRepository = operationContactRepository;
        this.callJobRepository = callJobRepository;
        this.callJobDispatcher = callJobDispatcher;
        this.validator = validator;
        this.allowDuplicatePhoneNumbersForDev = allowDuplicatePhoneNumbersForDev;
    }

    @Override
    @Transactional
    public List<OperationContactResponseDto> uploadContacts(
            UUID companyId,
            UUID operationId,
            UploadOperationContactsRequest request
    ) {
        validateRequest(request);

        Operation operation = getOperation(companyId, operationId);
        Set<String> normalizedPhoneNumbers = ensureNoDuplicatePhoneNumbers(request.getContacts());
        ensurePhoneNumbersDoNotAlreadyExist(operation, normalizedPhoneNumbers);

        List<OperationContact> contacts = request.getContacts().stream()
                .map(input -> buildContact(operation, input))
                .toList();

        try {
            List<OperationContact> savedContacts = operationContactRepository.saveAllAndFlush(contacts);
            appendStartedOperationJobs(operation, savedContacts);
            return savedContacts.stream().map(this::toDto).toList();
        } catch (DataIntegrityViolationException ex) {
            throw new ValidationException("This operation already has a contact with the same normalized phone number.");
        }
    }

    @Override
    public List<OperationContactResponseDto> listContacts(UUID companyId, UUID operationId) {
        getOperation(companyId, operationId);

        return operationContactRepository
                .findAllByOperation_IdAndCompany_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId, companyId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public OperationContactSummaryResponseDto getContactSummary(UUID companyId, UUID operationId, int latestLimit) {
        getOperation(companyId, operationId);

        List<OperationContact> contacts = operationContactRepository
                .findAllByOperation_IdAndCompany_IdAndDeletedAtIsNullOrderByCreatedAtDesc(operationId, companyId);

        List<OperationContactStatusCountDto> statusCounts = List.of(OperationContactStatus.values()).stream()
                .map(status -> new OperationContactStatusCountDto(
                        status,
                        contacts.stream().filter(contact -> contact.getStatus() == status).count()
                ))
                .filter(item -> item.count() > 0)
                .toList();

        List<OperationContactResponseDto> latestContacts = contacts.stream()
                .limit(Math.max(0, latestLimit))
                .map(this::toDto)
                .toList();

        long totalContactCount = contacts.size();
        OperationContactReadinessDto readiness = totalContactCount > 0
                ? new OperationContactReadinessDto(true, "READY", "Operation has contacts and is ready for contact-based workflows.")
                : new OperationContactReadinessDto(false, "MISSING_CONTACTS", "Operation exists but does not have any contacts yet.");

        return new OperationContactSummaryResponseDto(
                totalContactCount,
                totalContactCount,
                statusCounts,
                latestContacts,
                readiness
        );
    }

    @Override
    public OperationContactPageResponseDto listContactsPage(
            UUID companyId,
            UUID operationId,
            int page,
            int size,
            String query,
            OperationContactStatus status
    ) {
        getOperation(companyId, operationId);

        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim();

        Specification<OperationContact> specification = buildOperationContactSpecification(
                operationId,
                companyId,
                status,
                normalizedQuery
        );

        var pageResult = operationContactRepository.findAll(
                specification,
                PageRequest.of(normalizedPage, normalizedSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        return new OperationContactPageResponseDto(
                pageResult.getContent().stream().map(this::toDto).toList(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages(),
                pageResult.getNumber(),
                pageResult.getSize()
        );
    }

    private Specification<OperationContact> buildOperationContactSpecification(
            UUID operationId,
            UUID companyId,
            OperationContactStatus status,
            String query
    ) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new java.util.ArrayList<>();

            predicates.add(criteriaBuilder.equal(root.get("operation").get("id"), operationId));
            predicates.add(criteriaBuilder.equal(root.get("company").get("id"), companyId));
            predicates.add(criteriaBuilder.isNull(root.get("deletedAt")));

            if (status != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }

            if (query != null) {
                String likeQuery = "%" + query.toLowerCase() + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(root.get("firstName"), "")), likeQuery),
                        criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(root.get("lastName"), "")), likeQuery),
                        criteriaBuilder.like(root.get("phoneNumber"), "%" + query + "%")
                ));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void validateRequest(UploadOperationContactsRequest request) {
        Set<ConstraintViolation<UploadOperationContactsRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations.iterator().next().getMessage());
        }
    }

    private Operation getOperation(UUID companyId, UUID operationId) {
        return operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(operationId, companyId)
                .orElseThrow(() -> new NotFoundException("Operation not found for company: " + operationId));
    }

    private Set<String> ensureNoDuplicatePhoneNumbers(List<OperationContactInput> contacts) {
        if (allowDuplicatePhoneNumbersForDev) {
            Set<String> normalizedPhoneNumbers = new HashSet<>();
            for (OperationContactInput input : contacts) {
                String normalizedPhoneNumber = normalizePhoneNumber(input.getPhoneNumber());
                validatePhoneNumber(normalizedPhoneNumber);
                normalizedPhoneNumbers.add(normalizedPhoneNumber);
            }
            return normalizedPhoneNumbers;
        }

        Set<String> seenPhoneNumbers = new HashSet<>();

        for (OperationContactInput input : contacts) {
            String normalizedPhoneNumber = normalizePhoneNumber(input.getPhoneNumber());
            validatePhoneNumber(normalizedPhoneNumber);

            if (!seenPhoneNumbers.add(normalizedPhoneNumber)) {
                throw new ValidationException("Duplicate phone number in upload payload: " + normalizedPhoneNumber);
            }
        }

        return seenPhoneNumbers;
    }

    private void ensurePhoneNumbersDoNotAlreadyExist(Operation operation, Set<String> normalizedPhoneNumbers) {
        if (allowDuplicatePhoneNumbersForDev) {
            return;
        }
        if (normalizedPhoneNumbers.isEmpty()) {
            return;
        }

        List<OperationContact> existingContacts = operationContactRepository
                .findAllByOperation_IdAndCompany_IdAndPhoneNumberInAndDeletedAtIsNull(
                        operation.getId(),
                        operation.getCompany().getId(),
                        normalizedPhoneNumbers
                );

        if (!existingContacts.isEmpty()) {
            throw new ValidationException(
                    "Phone number already exists in this operation: " + existingContacts.get(0).getPhoneNumber()
            );
        }
    }

    private OperationContact buildContact(Operation operation, OperationContactInput input) {
        String normalizedPhoneNumber = normalizePhoneNumber(input.getPhoneNumber());
        validatePhoneNumber(normalizedPhoneNumber);

        OperationContact contact = new OperationContact();
        contact.setCompany(operation.getCompany());
        contact.setOperation(operation);
        contact.setFirstName(input.getName().trim());
        if (allowDuplicatePhoneNumbersForDev) {
            contact.setPhoneNumber(generateSyntheticStoredPhoneNumber());
            contact.setMetadataJson(OperationContactPhoneResolver.augmentMetadataWithOriginalPhone("{}", normalizedPhoneNumber));
        } else {
            contact.setPhoneNumber(normalizedPhoneNumber);
            contact.setMetadataJson("{}");
        }
        contact.setStatus(OperationContactStatus.PENDING);
        contact.setRetryCount(0);
        return contact;
    }

    private void appendStartedOperationJobs(Operation operation, List<OperationContact> contacts) {
        if (contacts.isEmpty()) {
            return;
        }

        if (operation.getStatus() != OperationStatus.RUNNING && operation.getStatus() != OperationStatus.PAUSED) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<CallJob> jobs = contacts.stream()
                .map(contact -> buildPendingJob(operation, contact, now))
                .toList();
        callJobRepository.saveAll(jobs);

        if (operation.getStatus() == OperationStatus.RUNNING) {
            callJobDispatcher.dispatchNextPreparedJob(operation.getId());
        }
    }

    private CallJob buildPendingJob(Operation operation, OperationContact contact, OffsetDateTime scheduledFor) {
        CallJob job = new CallJob();
        job.setCompany(operation.getCompany());
        job.setOperation(operation);
        job.setOperationContact(contact);
        job.setStatus(CallJobStatus.PENDING);
        job.setPriority((short) 5);
        job.setScheduledFor(scheduledFor);
        job.setAvailableAt(scheduledFor);
        job.setAttemptCount(0);
        job.setMaxAttempts(3);
        job.setIdempotencyKey(operation.getId() + ":" + contact.getId());
        return job;
    }

    private void validatePhoneNumber(String phoneNumber) {
        if (!PhoneNumberNormalizer.isValid(phoneNumber)) {
            throw new ValidationException("Invalid phone number format. Use a normalized number like 905551112233");
        }
    }

    private String normalizePhoneNumber(String phoneNumber) {
        return PhoneNumberNormalizer.normalize(phoneNumber);
    }

    private String generateSyntheticStoredPhoneNumber() {
        long value = Math.abs(ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999_999L));
        return Long.toString(value);
    }

    private OperationContactResponseDto toDto(OperationContact contact) {
        return new OperationContactResponseDto(
                contact.getId(),
                contact.getCompany().getId(),
                contact.getOperation().getId(),
                contact.getFirstName(),
                OperationContactPhoneResolver.resolveDisplayPhoneNumber(contact),
                contact.getStatus(),
                contact.getCreatedAt(),
                contact.getUpdatedAt()
        );
    }
}
