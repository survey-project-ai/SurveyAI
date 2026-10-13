package com.yourcompany.surveyai.operation.repository;

import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperationContactRepository extends JpaRepository<OperationContact, UUID>, JpaSpecificationExecutor<OperationContact> {

    List<OperationContact> findAllByOperation_IdAndDeletedAtIsNull(UUID operationId);

    List<OperationContact> findAllByOperation_IdAndCompany_IdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID operationId, UUID companyId);

    long countByOperation_IdAndCompany_IdAndDeletedAtIsNull(UUID operationId, UUID companyId);

    List<OperationContact> findAllByOperation_IdAndStatusAndDeletedAtIsNull(UUID operationId, OperationContactStatus status);

    List<OperationContact> findAllByCompany_IdAndNextRetryAtBeforeAndDeletedAtIsNull(UUID companyId, OffsetDateTime nextRetryAt);

    List<OperationContact> findAllByOperation_IdAndCompany_IdAndPhoneNumberInAndDeletedAtIsNull(
            UUID operationId,
            UUID companyId,
            Collection<String> phoneNumbers
    );

    Optional<OperationContact> findByOperation_IdAndExternalRefAndDeletedAtIsNull(UUID operationId, String externalRef);

    @Query("""
            SELECT c.status, COUNT(c)
            FROM OperationContact c
            WHERE c.company.id = :companyId AND c.deletedAt IS NULL AND c.operation.deletedAt IS NULL
            GROUP BY c.status
            """)
    List<Object[]> countGroupedByStatusForCompany(@Param("companyId") UUID companyId);
}
