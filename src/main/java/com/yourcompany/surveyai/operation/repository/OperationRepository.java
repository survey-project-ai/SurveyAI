package com.yourcompany.surveyai.operation.repository;

import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.enums.OperationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OperationRepository extends JpaRepository<Operation, UUID> {

    // Locks the operation row for the call dispatcher. A lock timeout of -2 is Hibernate's SKIP LOCKED,
    // so a dispatcher on another thread or instance that already holds the row gets an empty result
    // instead of waiting, and two dispatchers can never claim calls for the same operation at once.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT o FROM Operation o WHERE o.id = :id")
    Optional<Operation> findAndLockForDispatch(@Param("id") UUID id);

    @Query("SELECT o.id FROM Operation o WHERE o.status = :status AND o.deletedAt IS NULL")
    List<UUID> findIdsByStatusAndDeletedAtIsNull(@Param("status") OperationStatus status);

    List<Operation> findAllByCompany_IdAndDeletedAtIsNull(UUID companyId);

    List<Operation> findAllByCompany_IdAndStatusAndDeletedAtIsNull(UUID companyId, OperationStatus status);

    List<Operation> findAllByCompany_IdAndScheduledAtBeforeAndDeletedAtIsNull(UUID companyId, OffsetDateTime scheduledAt);

    Optional<Operation> findByIdAndCompany_IdAndDeletedAtIsNull(UUID id, UUID companyId);

    boolean existsBySurvey_IdAndDeletedAtIsNull(UUID surveyId);
}
