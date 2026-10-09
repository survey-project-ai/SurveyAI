package com.yourcompany.surveyai.call.repository;

import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallAttemptRepository extends JpaRepository<CallAttempt, UUID> {

    List<CallAttempt> findAllByCallJob_IdOrderByCreatedAtDesc(UUID callJobId);

    List<CallAttempt> findAllByOperationContact_IdOrderByCreatedAtDesc(UUID operationContactId);

    List<CallAttempt> findAllByCompany_IdAndStatusAndDeletedAtIsNull(UUID companyId, CallAttemptStatus status);

    java.util.Optional<CallAttempt> findTopByCallJob_IdAndDeletedAtIsNullOrderByAttemptNumberDesc(UUID callJobId);

    Optional<CallAttempt> findByProviderAndProviderCallIdAndDeletedAtIsNull(CallProvider provider, String providerCallId);

    Optional<CallAttempt> findByIdAndDeletedAtIsNull(UUID id);

    // The call job is fetched because reconciliation reads it after the query's transaction has ended.
    @EntityGraph(attributePaths = "callJob")
    List<CallAttempt> findTop100ByProviderAndStatusInAndProviderCallIdIsNotNullAndDeletedAtIsNullAndDialedAtBeforeOrderByDialedAtAsc(
            CallProvider provider,
            Collection<CallAttemptStatus> statuses,
            OffsetDateTime dialedAt
    );
}
