package com.yourcompany.surveyai.response.repository;

import com.yourcompany.surveyai.response.domain.entity.SurveyResponse;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SurveyResponseRepository extends JpaRepository<SurveyResponse, UUID> {

    List<SurveyResponse> findAllByCompany_IdAndSurvey_IdAndStatusAndDeletedAtIsNull(
            UUID companyId,
            UUID surveyId,
            SurveyResponseStatus status
    );

    List<SurveyResponse> findAllByOperation_IdOrderByCreatedAtDesc(UUID operationId);

    List<SurveyResponse> findAllByOperation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID operationId);

    @Query("""
            SELECT new com.yourcompany.surveyai.response.repository.SurveyResponseAnalyticsRow(
                r.id, contact.id, contact.firstName, contact.lastName, attempt.callJob.id,
                r.status, r.completionPercent, r.startedAt, r.completedAt, r.createdAt,
                cast(function('jsonb_extract_path_text', r.transcriptJson, 'openingConsentState') as String))
            FROM SurveyResponse r
            JOIN r.operationContact contact
            JOIN r.callAttempt attempt
            WHERE r.operation.id = :operationId AND r.deletedAt IS NULL
            ORDER BY r.createdAt DESC
            """)
    List<SurveyResponseAnalyticsRow> findAnalyticsRowsByOperationId(@Param("operationId") UUID operationId);

    Optional<SurveyResponse> findByCallAttempt_IdAndDeletedAtIsNull(UUID callAttemptId);

    List<SurveyResponse> findAllByCallAttempt_IdInAndDeletedAtIsNull(Collection<UUID> callAttemptIds);

    boolean existsBySurvey_IdAndDeletedAtIsNull(UUID surveyId);
}
