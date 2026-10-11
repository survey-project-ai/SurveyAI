package com.yourcompany.surveyai.response.repository;

import com.yourcompany.surveyai.response.domain.entity.SurveyAnswer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SurveyAnswerRepository extends JpaRepository<SurveyAnswer, UUID> {

    List<SurveyAnswer> findAllBySurveyResponse_IdAndDeletedAtIsNull(UUID surveyResponseId);

    List<SurveyAnswer> findAllBySurveyResponse_IdInAndDeletedAtIsNull(Collection<UUID> surveyResponseIds);

    // All answers of an operation's live responses in one query, without an IN list of response ids
    // (which fails above the JDBC parameter limit on large operations).
    @Query("""
            SELECT a FROM SurveyAnswer a
            WHERE a.surveyResponse.operation.id = :operationId
              AND a.surveyResponse.deletedAt IS NULL
              AND a.deletedAt IS NULL
            """)
    List<SurveyAnswer> findAllForOperationAnalytics(@Param("operationId") UUID operationId);

    List<SurveyAnswer> findAllBySurveyQuestion_IdAndValidAndDeletedAtIsNull(UUID surveyQuestionId, boolean valid);

    Optional<SurveyAnswer> findBySurveyResponse_IdAndSurveyQuestion_IdAndDeletedAtIsNull(UUID surveyResponseId, UUID surveyQuestionId);

    boolean existsBySurveyQuestion_IdAndDeletedAtIsNull(UUID surveyQuestionId);

    boolean existsBySelectedOption_IdAndDeletedAtIsNull(UUID optionId);
}
