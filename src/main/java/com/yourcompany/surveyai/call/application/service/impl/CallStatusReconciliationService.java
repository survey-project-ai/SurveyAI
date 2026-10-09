package com.yourcompany.surveyai.call.application.service.impl;

import com.yourcompany.surveyai.call.application.provider.CallProviderRegistry;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusRequest;
import com.yourcompany.surveyai.call.application.provider.ProviderCallStatusResult;
import com.yourcompany.surveyai.call.application.provider.VoiceExecutionProvider;
import com.yourcompany.surveyai.call.application.service.CallJobDispatcher;
import com.yourcompany.surveyai.call.application.service.ProviderExecutionObservationService;
import com.yourcompany.surveyai.call.application.support.CallStatusSnapshotApplier;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfiguration;
import com.yourcompany.surveyai.call.configuration.VoiceProviderConfigurationResolver;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// Polls the provider for attempts that have not reported a final status. Each provider request runs
// outside any transaction and each attempt is updated in its own short transaction.
@Service
public class CallStatusReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(CallStatusReconciliationService.class);
    private static final Set<CallAttemptStatus> OPEN_ATTEMPT_STATUSES = EnumSet.of(
            CallAttemptStatus.INITIATED,
            CallAttemptStatus.RINGING,
            CallAttemptStatus.IN_PROGRESS
    );

    private final CallProviderRegistry callProviderRegistry;
    private final VoiceProviderConfigurationResolver configurationResolver;
    private final CallAttemptRepository callAttemptRepository;
    private final ProviderExecutionObservationService providerExecutionObservationService;
    private final CallStatusSnapshotApplier callStatusSnapshotApplier;
    private final CallJobDispatcher callJobDispatcher;
    private final TransactionTemplate transactionTemplate;

    public CallStatusReconciliationService(
            CallProviderRegistry callProviderRegistry,
            VoiceProviderConfigurationResolver configurationResolver,
            CallAttemptRepository callAttemptRepository,
            ProviderExecutionObservationService providerExecutionObservationService,
            CallStatusSnapshotApplier callStatusSnapshotApplier,
            CallJobDispatcher callJobDispatcher,
            PlatformTransactionManager transactionManager
    ) {
        this.callProviderRegistry = callProviderRegistry;
        this.configurationResolver = configurationResolver;
        this.callAttemptRepository = callAttemptRepository;
        this.providerExecutionObservationService = providerExecutionObservationService;
        this.callStatusSnapshotApplier = callStatusSnapshotApplier;
        this.callJobDispatcher = callJobDispatcher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(
            fixedDelayString = "${surveyai.calling.status-reconciliation.fixed-delay-ms:30000}",
            initialDelayString = "${surveyai.calling.status-reconciliation.initial-delay-ms:15000}"
    )
    public void reconcileOpenAttempts() {
        VoiceProviderConfiguration configuration = configurationResolver.getActiveConfiguration();
        if (!configuration.enabled() || configuration.mockMode()) {
            return;
        }

        VoiceExecutionProvider provider = callProviderRegistry.getRequiredProvider(configuration.provider());
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(resolveGracePeriodSeconds(configuration));
        List<CallAttempt> attempts = callAttemptRepository
                .findTop100ByProviderAndStatusInAndProviderCallIdIsNotNullAndDeletedAtIsNullAndDialedAtBeforeOrderByDialedAtAsc(
                        configuration.provider(),
                        OPEN_ATTEMPT_STATUSES,
                        cutoff
                );

        for (CallAttempt attempt : attempts) {
            reconcileAttempt(provider, configuration, attempt);
        }
    }

    private void reconcileAttempt(
            VoiceExecutionProvider provider,
            VoiceProviderConfiguration configuration,
            CallAttempt attempt
    ) {
        ProviderCallStatusResult statusResult;
        try {
            statusResult = provider.fetchCallStatus(new ProviderCallStatusRequest(attempt), configuration);
        } catch (RuntimeException error) {
            transactionTemplate.executeWithoutResult(status -> callAttemptRepository.findById(attempt.getId()).ifPresent(current ->
                    providerExecutionObservationService.recordStatusSnapshotFailure(
                            current.getCallJob(),
                            current,
                            provider.getProvider(),
                            error.getMessage()
                    )));
            log.warn(
                    "Provider status reconciliation failed. provider={} callAttemptId={} providerCallId={} message={}",
                    provider.getProvider(),
                    attempt.getId(),
                    attempt.getProviderCallId(),
                    error.getMessage()
            );
            return;
        }

        UUID operationToAdvance = transactionTemplate.execute(status -> applySnapshot(provider, attempt.getId(), statusResult));
        if (operationToAdvance != null) {
            callJobDispatcher.requestDispatch(operationToAdvance);
        }
    }

    // Returns the operation whose queue can move on when the snapshot finished the attempt.
    private UUID applySnapshot(VoiceExecutionProvider provider, UUID attemptId, ProviderCallStatusResult statusResult) {
        CallAttempt attempt = callAttemptRepository.findByIdAndDeletedAtIsNull(attemptId).orElse(null);
        if (attempt == null) {
            return null;
        }
        providerExecutionObservationService.recordStatusSnapshot(
                attempt.getCallJob(),
                attempt,
                provider.getProvider(),
                statusResult,
                "Periodic provider status reconciliation"
        );

        CallJobStatus previousJobStatus = attempt.getCallJob().getStatus();
        CallAttemptStatus previousAttemptStatus = attempt.getStatus();
        boolean finished = callStatusSnapshotApplier.apply(attempt, statusResult);

        if (previousJobStatus != attempt.getCallJob().getStatus() || previousAttemptStatus != attempt.getStatus()) {
            log.info(
                    "Provider status reconciliation applied. provider={} callJobId={} callAttemptId={} providerCallId={} previousJobStatus={} newJobStatus={} previousAttemptStatus={} newAttemptStatus={}",
                    attempt.getProvider(),
                    attempt.getCallJob().getId(),
                    attempt.getId(),
                    attempt.getProviderCallId(),
                    previousJobStatus,
                    attempt.getCallJob().getStatus(),
                    previousAttemptStatus,
                    attempt.getStatus()
            );
        }
        return finished ? attempt.getOperation().getId() : null;
    }

    private long resolveGracePeriodSeconds(VoiceProviderConfiguration configuration) {
        String configured = configuration.settings().get("status-reconciliation-grace-seconds");
        if (configured == null || configured.isBlank()) {
            return 20L;
        }
        try {
            return Math.max(5L, Long.parseLong(configured.trim()));
        } catch (NumberFormatException ignored) {
            return 20L;
        }
    }
}
