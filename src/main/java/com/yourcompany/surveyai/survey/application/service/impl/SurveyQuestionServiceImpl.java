package com.yourcompany.surveyai.survey.application.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.common.repository.CompanyRepository;
import com.yourcompany.surveyai.response.repository.SurveyAnswerRepository;
import com.yourcompany.surveyai.survey.application.dto.request.CreateSurveyQuestionRequest;
import com.yourcompany.surveyai.survey.application.dto.request.UpdateSurveyQuestionRequest;
import com.yourcompany.surveyai.survey.application.dto.response.SurveyQuestionOptionResponseDto;
import com.yourcompany.surveyai.survey.application.dto.response.SurveyQuestionResponseDto;
import com.yourcompany.surveyai.survey.application.service.SurveyQuestionService;
import com.yourcompany.surveyai.survey.application.support.SurveyQuestionAutoLexiconService;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.domain.enums.SurveyStatus;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionOptionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SurveyQuestionServiceImpl implements SurveyQuestionService {

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository surveyQuestionRepository;
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository;
    private final SurveyAnswerRepository surveyAnswerRepository;
    private final CompanyRepository companyRepository;
    private final Validator validator;
    private final ObjectMapper objectMapper;
    private final SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService;

    public SurveyQuestionServiceImpl(
            SurveyRepository surveyRepository,
            SurveyQuestionRepository surveyQuestionRepository,
            SurveyQuestionOptionRepository surveyQuestionOptionRepository,
            SurveyAnswerRepository surveyAnswerRepository,
            CompanyRepository companyRepository,
            Validator validator,
            ObjectMapper objectMapper,
            SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService
    ) {
        this.surveyRepository = surveyRepository;
        this.surveyQuestionRepository = surveyQuestionRepository;
        this.surveyQuestionOptionRepository = surveyQuestionOptionRepository;
        this.surveyAnswerRepository = surveyAnswerRepository;
        this.companyRepository = companyRepository;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.surveyQuestionAutoLexiconService = surveyQuestionAutoLexiconService;
    }

    @Override
    @Transactional
    public SurveyQuestionResponseDto addQuestion(UUID companyId, UUID surveyId, CreateSurveyQuestionRequest request) {
        validateRequest(request);

        Survey survey = getDraftSurvey(companyId, surveyId);
        ensureQuestionCodeAvailable(surveyId, request.getCode(), null);
        ensureQuestionOrderAvailable(surveyId, request.getQuestionOrder(), null);

        SurveyQuestion question = new SurveyQuestion();
        question.setCompany(survey.getCompany());
        question.setSurvey(survey);
        applyQuestionFields(question, request);
        question.setSettingsJson(surveyQuestionAutoLexiconService.rebuildSettingsJson(question, List.of()));

        return toDto(surveyQuestionRepository.save(question));
    }

    @Override
    @Transactional
    public SurveyQuestionResponseDto updateQuestion(
            UUID companyId,
            UUID surveyId,
            UUID questionId,
            UpdateSurveyQuestionRequest request
    ) {
        validateRequest(request);

        SurveyQuestion question = getQuestion(companyId, surveyId, questionId);
        ensureDraftSurvey(question.getSurvey());
        ensureQuestionCodeAvailable(surveyId, request.getCode(), questionId);
        ensureQuestionOrderAvailable(surveyId, request.getQuestionOrder(), questionId);
        validateQuestionTypeTransition(question, request.getQuestionType());

        applyQuestionFields(question, request);
        question.setSettingsJson(surveyQuestionAutoLexiconService.rebuildSettingsJson(
                question,
                surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(question.getId())
        ));
        return toDto(surveyQuestionRepository.save(question));
    }

    @Override
    @Transactional
    public void deleteQuestion(UUID companyId, UUID surveyId, UUID questionId) {
        SurveyQuestion question = getQuestion(companyId, surveyId, questionId);
        ensureDraftSurvey(question.getSurvey());

        if (surveyAnswerRepository.existsBySurveyQuestion_IdAndDeletedAtIsNull(questionId)) {
            throw new ValidationException("Bu soruya verilmiş cevaplar olduğu için soru silinemez.");
        }

        for (SurveyQuestionOption option : surveyQuestionOptionRepository
                .findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(questionId)) {
            surveyQuestionOptionRepository.delete(option);
        }
        surveyQuestionRepository.delete(question);
    }

    @Override
    public List<SurveyQuestionResponseDto> listQuestions(UUID companyId, UUID surveyId) {
        ensureSurveyExists(companyId, surveyId);

        return surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(surveyId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private void validateRequest(CreateSurveyQuestionRequest request) {
        Set<ConstraintViolation<CreateSurveyQuestionRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations.iterator().next().getMessage());
        }
        validateJsonPayload(request.getBranchConditionJson(), "branchConditionJson");
        validateJsonPayload(request.getSettingsJson(), "settingsJson");
    }

    private void validateRequest(UpdateSurveyQuestionRequest request) {
        Set<ConstraintViolation<UpdateSurveyQuestionRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations.iterator().next().getMessage());
        }
        validateJsonPayload(request.getBranchConditionJson(), "branchConditionJson");
        validateJsonPayload(request.getSettingsJson(), "settingsJson");
    }

    private Survey getDraftSurvey(UUID companyId, UUID surveyId) {
        Survey survey = ensureSurveyExists(companyId, surveyId);
        ensureDraftSurvey(survey);
        return survey;
    }

    private Survey ensureSurveyExists(UUID companyId, UUID surveyId) {
        if (!companyRepository.existsById(companyId)) {
            throw new NotFoundException("Şirket bulunamadı: " + companyId);
        }

        return surveyRepository.findByIdAndCompany_IdAndDeletedAtIsNull(surveyId, companyId)
                .orElseThrow(() -> new NotFoundException("Bu şirkette anket bulunamadı: " + surveyId));
    }

    private SurveyQuestion getQuestion(UUID companyId, UUID surveyId, UUID questionId) {
        ensureSurveyExists(companyId, surveyId);
        return surveyQuestionRepository.findByIdAndSurvey_IdAndCompany_IdAndDeletedAtIsNull(questionId, surveyId, companyId)
                .orElseThrow(() -> new NotFoundException("Anket sorusu bulunamadı: " + questionId));
    }

    private void ensureDraftSurvey(Survey survey) {
        if (survey.getStatus() != SurveyStatus.DRAFT) {
            throw new ValidationException("Anket soruları yalnızca anket taslak durumundayken değiştirilebilir.");
        }
    }

    private void ensureQuestionCodeAvailable(UUID surveyId, String code, UUID currentQuestionId) {
        surveyQuestionRepository.findBySurvey_IdAndCodeAndDeletedAtIsNull(
                surveyId,
                requireTrimmed(code, "Soru kodu zorunludur.")
        )
                .ifPresent(existing -> {
                    if (!existing.getId().equals(currentQuestionId)) {
                        throw new ValidationException("Bu ankette aynı soru kodu zaten var.");
                    }
                });
    }

    private void ensureQuestionOrderAvailable(UUID surveyId, Integer questionOrder, UUID currentQuestionId) {
        surveyQuestionRepository.findBySurvey_IdAndQuestionOrderAndDeletedAtIsNull(surveyId, questionOrder)
                .ifPresent(existing -> {
                    if (!existing.getId().equals(currentQuestionId)) {
                        throw new ValidationException("Bu ankette aynı soru sırası zaten var.");
                    }
                });
    }

    private void validateQuestionTypeTransition(SurveyQuestion question, QuestionType newType) {
        if (isChoiceQuestion(newType)) {
            return;
        }

        boolean hasOptions = !surveyQuestionOptionRepository
                .findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(question.getId())
                .isEmpty();
        if (hasOptions) {
            throw new ValidationException("Soruyu seçimli olmayan bir tipe çevirmeden önce seçeneklerini silin.");
        }
    }

    private void applyQuestionFields(SurveyQuestion question, CreateSurveyQuestionRequest request) {
        question.setCode(requireTrimmed(request.getCode(), "Soru kodu zorunludur."));
        question.setQuestionOrder(request.getQuestionOrder());
        question.setQuestionType(request.getQuestionType());
        question.setTitle(requireTrimmed(request.getTitle(), "Soru başlığı zorunludur."));
        question.setDescription(trimToNull(request.getDescription()));
        question.setRequired(request.isRequired());
        question.setRetryPrompt(trimToNull(request.getRetryPrompt()));
        question.setBranchConditionJson(normalizeJson(request.getBranchConditionJson()));
        question.setSettingsJson(normalizeJson(request.getSettingsJson()));
        question.setSourceExternalId(trimToNull(request.getSourceExternalId()));
        question.setSourcePayloadJson(trimToNull(request.getSourcePayloadJson()));
    }

    private void applyQuestionFields(SurveyQuestion question, UpdateSurveyQuestionRequest request) {
        question.setCode(requireTrimmed(request.getCode(), "Soru kodu zorunludur."));
        question.setQuestionOrder(request.getQuestionOrder());
        question.setQuestionType(request.getQuestionType());
        question.setTitle(requireTrimmed(request.getTitle(), "Soru başlığı zorunludur."));
        question.setDescription(trimToNull(request.getDescription()));
        question.setRequired(request.isRequired());
        question.setRetryPrompt(trimToNull(request.getRetryPrompt()));
        question.setBranchConditionJson(normalizeJson(request.getBranchConditionJson()));
        question.setSettingsJson(normalizeJson(request.getSettingsJson()));
        question.setSourceExternalId(trimToNull(request.getSourceExternalId()));
        question.setSourcePayloadJson(trimToNull(request.getSourcePayloadJson()));
    }

    private SurveyQuestionResponseDto toDto(SurveyQuestion question) {
        List<SurveyQuestionOptionResponseDto> options = surveyQuestionOptionRepository
                .findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(question.getId())
                .stream()
                .map(this::toOptionDto)
                .toList();

        return new SurveyQuestionResponseDto(
                question.getId(),
                question.getSurvey().getId(),
                question.getCompany().getId(),
                question.getCode(),
                question.getQuestionOrder(),
                question.getQuestionType(),
                question.getTitle(),
                question.getDescription(),
                question.isRequired(),
                question.getRetryPrompt(),
                question.getBranchConditionJson(),
                question.getSettingsJson(),
                question.getSourceExternalId(),
                question.getSourcePayloadJson(),
                options,
                question.getCreatedAt(),
                question.getUpdatedAt()
        );
    }

    private SurveyQuestionOptionResponseDto toOptionDto(SurveyQuestionOption option) {
        return new SurveyQuestionOptionResponseDto(
                option.getId(),
                option.getSurveyQuestion().getId(),
                option.getCompany().getId(),
                option.getOptionOrder(),
                option.getOptionCode(),
                option.getLabel(),
                option.getValue(),
                option.isActive(),
                option.getCreatedAt(),
                option.getUpdatedAt()
        );
    }

    private void validateJsonPayload(String payload, String fieldName) {
        String trimmed = trimToNull(payload);
        if (trimmed == null) {
            return;
        }

        try {
            objectMapper.readTree(trimmed);
        } catch (JsonProcessingException ex) {
            throw new ValidationException(fieldName + " alanı geçerli bir JSON olmalıdır.");
        }
    }

    private String normalizeJson(String payload) {
        String trimmed = trimToNull(payload);
        return trimmed != null ? trimmed : "{}";
    }

    private boolean isChoiceQuestion(QuestionType questionType) {
        return questionType == QuestionType.SINGLE_CHOICE || questionType == QuestionType.MULTI_CHOICE;
    }

    private String requireTrimmed(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ValidationException(message);
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
