package com.yourcompany.surveyai.call.repository;

import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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

    long countByOperation_IdAndStatusInAndDeletedAtIsNull(UUID operationId, Set<CallJobStatus> statuses);

    List<CallJob> findAllByOperation_IdAndStatusInAndUpdatedAtBeforeAndDeletedAtIsNull(
            UUID operationId,
            Set<CallJobStatus> statuses,
            OffsetDateTime updatedBefore
    );

    Optional<CallJob> findFirstByOperation_IdAndStatusInAndAvailableAtBeforeAndDeletedAtIsNullOrderByPriorityDescScheduledForAscCreatedAtAsc(
            UUID operationId,
            Set<CallJobStatus> statuses,
            OffsetDateTime availableAt
    );

    @Query("SELECT j.status, COUNT(j) FROM CallJob j WHERE j.operation.id = :operationId AND j.deletedAt IS NULL GROUP BY j.status")
    List<Object[]> countGroupedByStatusForOperation(@Param("operationId") UUID operationId);

    // One row: contacts with at least one call attempt, and all call attempts of the operation.
    @Query("""
            SELECT COUNT(DISTINCT j.operationContact.id), COALESCE(SUM(j.attemptCount), 0)
            FROM CallJob j
            WHERE j.operation.id = :operationId AND j.deletedAt IS NULL AND j.attemptCount > 0
            """)
    List<Object[]> summarizeCallAttemptsForOperation(@Param("operationId") UUID operationId);

    @Query("""
            SELECT j.status, COUNT(j)
            FROM CallJob j
            WHERE j.company.id = :companyId AND j.deletedAt IS NULL AND j.operation.deletedAt IS NULL
            GROUP BY j.status
            """)
    List<Object[]> countGroupedByStatusForCompany(@Param("companyId") UUID companyId);

    @Query("""
            SELECT COALESCE(SUM(j.attemptCount), 0)
            FROM CallJob j
            WHERE j.company.id = :companyId AND j.deletedAt IS NULL AND j.operation.deletedAt IS NULL
            """)
    long sumCallAttemptsForCompany(@Param("companyId") UUID companyId);

    // Jobs that were dialed at least once, latest change first, with what the list shows already loaded.
    @Query("""
            SELECT j
            FROM CallJob j
            JOIN FETCH j.operation o
            JOIN FETCH j.operationContact
            WHERE j.company.id = :companyId AND j.deletedAt IS NULL AND o.deletedAt IS NULL AND j.attemptCount > 0
            ORDER BY j.updatedAt DESC
            """)
    List<CallJob> findRecentlyCalledForCompany(@Param("companyId") UUID companyId, Pageable pageable);
}
