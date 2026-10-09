package com.yourcompany.surveyai.call.repository;

import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CallJobRepository extends JpaRepository<CallJob, UUID>, JpaSpecificationExecutor<CallJob> {

    List<CallJob> findAllByCompany_IdAndStatusAndAvailableAtBeforeAndDeletedAtIsNull(
            UUID companyId,
            CallJobStatus status,
            OffsetDateTime availableAt
    );

    List<CallJob> findAllByOperation_IdAndDeletedAtIsNull(UUID operationId);

    List<CallJob> findAllByOperationContact_IdAndDeletedAtIsNull(UUID operationContactId);

    Optional<CallJob> findByIdAndOperation_IdAndCompany_IdAndDeletedAtIsNull(UUID id, UUID operationId, UUID companyId);

    Optional<CallJob> findByIdempotencyKeyAndDeletedAtIsNull(String idempotencyKey);

    boolean existsByOperation_IdAndStatusInAndDeletedAtIsNull(UUID operationId, Set<CallJobStatus> statuses);

    boolean existsByOperation_IdAndStatusInAndUpdatedAtAfterAndDeletedAtIsNull(
            UUID operationId,
            Set<CallJobStatus> statuses,
            OffsetDateTime updatedAfter
    );

    Optional<CallJob> findFirstByOperation_IdAndStatusInAndAvailableAtBeforeAndDeletedAtIsNullOrderByScheduledForAscCreatedAtAsc(
            UUID operationId,
            Set<CallJobStatus> statuses,
            OffsetDateTime availableAt
    );

    @Query("SELECT j.status, COUNT(j) FROM CallJob j WHERE j.operation.id = :operationId AND j.deletedAt IS NULL GROUP BY j.status")
    List<Object[]> countGroupedByStatusForOperation(@Param("operationId") UUID operationId);
}
