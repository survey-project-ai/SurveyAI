package com.yourcompany.surveyai.call.repository;

import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CallAttemptRepository extends JpaRepository<CallAttempt, UUID> {

    // Interview tool calls of one call take this row lock first, so a retried or doubled request waits
    // for the previous one to commit and then sees its answer instead of racing it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM CallAttempt a WHERE a.id = :id AND a.deletedAt IS NULL")
    Optional<CallAttempt> findAndLockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM CallAttempt a WHERE a.provider = :provider AND a.providerCallId = :providerCallId AND a.deletedAt IS NULL")
    Optional<CallAttempt> findAndLockByProviderCallId(
            @Param("provider") CallProvider provider,
            @Param("providerCallId") String providerCallId
    );

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
