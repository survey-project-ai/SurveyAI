package com.yourcompany.surveyai.operation.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.operation.application.dto.request.OperationContactInput;
import com.yourcompany.surveyai.operation.application.dto.request.UploadOperationContactsRequest;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OperationContactServiceImplTest {

    private final OperationRepository operationRepository = mock(OperationRepository.class);
    private final OperationContactRepository operationContactRepository = mock(OperationContactRepository.class);
    private final CallJobRepository callJobRepository = mock(CallJobRepository.class);
    private final CallJobDispatcher callJobDispatcher = mock(CallJobDispatcher.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private OperationContactServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OperationContactServiceImpl(
                operationRepository,
                operationContactRepository,
                callJobRepository,
                callJobDispatcher,
                validator,
                false
        );

        when(operationContactRepository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(callJobRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void uploadContacts_runningOperation_appendsPendingJobsAndTriggersDispatcher() {
        Fixture fixture = new Fixture(OperationStatus.RUNNING);
        UploadOperationContactsRequest request = buildRequest("Ayse Demir", "905551112233");

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(fixture.operation.getId(), fixture.company.getId()))
                .thenReturn(Optional.of(fixture.operation));
        when(operationContactRepository.findAllByOperation_IdAndCompany_IdAndPhoneNumberInAndDeletedAtIsNull(
                fixture.operation.getId(),
                fixture.company.getId(),
                List.of("905551112233")
        )).thenReturn(List.of());

        var response = service.uploadContacts(fixture.company.getId(), fixture.operation.getId(), request);

        assertThat(response).hasSize(1);
        verify(callJobRepository).saveAll(argThat(jobs -> {
            List<CallJob> savedJobs = new java.util.ArrayList<>();
            jobs.forEach(savedJobs::add);
            return savedJobs.size() == 1
                    && savedJobs.get(0).getStatus() == CallJobStatus.PENDING
                    && savedJobs.get(0).getOperation().getId().equals(fixture.operation.getId())
                    && savedJobs.get(0).getOperationContact().getPhoneNumber().equals("905551112233");
        }));
        verify(callJobDispatcher).requestDispatch(fixture.operation.getId());
    }

    @Test
    void uploadContacts_readyOperation_addsContactsWithoutPreparingJobs() {
        Fixture fixture = new Fixture(OperationStatus.READY);
        UploadOperationContactsRequest request = buildRequest("Ayse Demir", "905551112233");

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(fixture.operation.getId(), fixture.company.getId()))
                .thenReturn(Optional.of(fixture.operation));
        when(operationContactRepository.findAllByOperation_IdAndCompany_IdAndPhoneNumberInAndDeletedAtIsNull(
                fixture.operation.getId(),
                fixture.company.getId(),
                List.of("905551112233")
        )).thenReturn(List.of());

        var response = service.uploadContacts(fixture.company.getId(), fixture.operation.getId(), request);

        assertThat(response).hasSize(1);
        verify(callJobRepository, never()).saveAll(any());
        verify(callJobDispatcher, never()).requestDispatch(any());
    }

    @Test
    void uploadContacts_runningOperation_withMultipleContacts_createsPendingJobsForEachAndTriggersSingleDispatch() {
        Fixture fixture = new Fixture(OperationStatus.RUNNING);

        OperationContactInput first = new OperationContactInput();
        first.setName("Ayse Demir");
        first.setPhoneNumber("905551112233");

        OperationContactInput second = new OperationContactInput();
        second.setName("Mehmet Kaya");
        second.setPhoneNumber("905551112244");

        UploadOperationContactsRequest request = new UploadOperationContactsRequest();
        request.setContacts(List.of(first, second));

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(fixture.operation.getId(), fixture.company.getId()))
                .thenReturn(Optional.of(fixture.operation));
        when(operationContactRepository.findAllByOperation_IdAndCompany_IdAndPhoneNumberInAndDeletedAtIsNull(
                fixture.operation.getId(),
                fixture.company.getId(),
                List.of("905551112233", "905551112244")
        )).thenReturn(List.of());

        var response = service.uploadContacts(fixture.company.getId(), fixture.operation.getId(), request);

        assertThat(response).hasSize(2);
        verify(callJobRepository).saveAll(argThat(jobs -> {
            List<CallJob> savedJobs = new java.util.ArrayList<>();
            jobs.forEach(savedJobs::add);
            return savedJobs.size() == 2
                    && savedJobs.stream().allMatch(job -> job.getStatus() == CallJobStatus.PENDING)
                    && savedJobs.stream().allMatch(job -> job.getOperation().getId().equals(fixture.operation.getId()))
                    && savedJobs.stream().map(job -> job.getOperationContact().getPhoneNumber()).toList()
                    .containsAll(List.of("905551112233", "905551112244"));
        }));
        verify(callJobDispatcher).requestDispatch(fixture.operation.getId());
    }

    @Test
    void uploadContacts_devFlagEnabled_allowsDuplicatePhoneNumbersWithinSamePayload() {
        service = new OperationContactServiceImpl(
                operationRepository,
                operationContactRepository,
                callJobRepository,
                callJobDispatcher,
                validator,
                true
        );

        Fixture fixture = new Fixture(OperationStatus.RUNNING);

        OperationContactInput first = new OperationContactInput();
        first.setName("Ayse Demir");
        first.setPhoneNumber("905551112233");

        OperationContactInput second = new OperationContactInput();
        second.setName("Mehmet Kaya");
        second.setPhoneNumber("905551112233");

        UploadOperationContactsRequest request = new UploadOperationContactsRequest();
        request.setContacts(List.of(first, second));

        when(operationRepository.findByIdAndCompany_IdAndDeletedAtIsNull(fixture.operation.getId(), fixture.company.getId()))
                .thenReturn(Optional.of(fixture.operation));

        var response = service.uploadContacts(fixture.company.getId(), fixture.operation.getId(), request);

        assertThat(response).hasSize(2);
        assertThat(response).extracting("phoneNumber").containsExactly("905551112233", "905551112233");
        verify(callJobRepository).saveAll(argThat(jobs -> {
            List<CallJob> savedJobs = new java.util.ArrayList<>();
            jobs.forEach(savedJobs::add);
            return savedJobs.size() == 2
                    && savedJobs.stream().map(job -> job.getOperationContact().getPhoneNumber()).distinct().count() == 2
                    && savedJobs.stream().allMatch(job -> !job.getOperationContact().getPhoneNumber().equals("905551112233"))
                    && savedJobs.stream().allMatch(job -> job.getOperationContact().getMetadataJson().contains("\"originalPhoneNumber\":\"905551112233\""));
        }));
        verify(callJobDispatcher).requestDispatch(fixture.operation.getId());
    }

    private static UploadOperationContactsRequest buildRequest(String name, String phoneNumber) {
        OperationContactInput input = new OperationContactInput();
        input.setName(name);
        input.setPhoneNumber(phoneNumber);

        UploadOperationContactsRequest request = new UploadOperationContactsRequest();
        request.setContacts(List.of(input));
        return request;
    }

    private static final class Fixture {
        private final Company company = new Company();
        private final Survey survey = new Survey();
        private final Operation operation = new Operation();

        private Fixture(OperationStatus status) {
            company.setId(UUID.randomUUID());

            survey.setId(UUID.randomUUID());
            survey.setCompany(company);
            survey.setName("Memnuniyet");

            operation.setId(UUID.randomUUID());
            operation.setCompany(company);
            operation.setSurvey(survey);
            operation.setName("Nisan Operasyonu");
            operation.setStatus(status);
            operation.setStartedAt(status == OperationStatus.RUNNING || status == OperationStatus.PAUSED ? OffsetDateTime.now().minusMinutes(15) : null);
        }
    }
}
