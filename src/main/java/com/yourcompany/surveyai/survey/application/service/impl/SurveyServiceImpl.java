package com.yourcompany.surveyai.survey.application.service.impl;

import com.yourcompany.surveyai.auth.application.RequestAuthContext;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.common.domain.entity.AppUser;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.common.repository.AppUserRepository;
import com.yourcompany.surveyai.common.repository.CompanyRepository;
import com.yourcompany.surveyai.response.repository.SurveyResponseRepository;
import com.yourcompany.surveyai.survey.application.dto.request.CreateSurveyRequest;
import com.yourcompany.surveyai.survey.application.dto.request.UpdateSurveyRequest;
import com.yourcompany.surveyai.survey.application.dto.response.SurveyResponseDto;
import com.yourcompany.surveyai.survey.application.service.SurveyService;
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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SurveyServiceImpl implements SurveyService {

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository surveyQuestionRepository;
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository;
    private final CompanyRepository companyRepository;
    private final OperationRepository operationRepository;
    private final SurveyResponseRepository surveyResponseRepository;
    private final AppUserRepository appUserRepository;
    private final RequestAuthContext requestAuthContext;
    private final Validator validator;
    private final SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService;

    public SurveyServiceImpl(
            SurveyRepository surveyRepository,
            SurveyQuestionRepository surveyQuestionRepository,
            SurveyQuestionOptionRepository surveyQuestionOptionRepository,
            CompanyRepository companyRepository,
            OperationRepository operationRepository,
            SurveyResponseRepository surveyResponseRepository,
            AppUserRepository appUserRepository,
            RequestAuthContext requestAuthContext,
            Validator validator,
            SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService
    ) {
        this.surveyRepository = surveyRepository;
        this.surveyQuestionRepository = surveyQuestionRepository;
        this.surveyQuestionOptionRepository = surveyQuestionOptionRepository;
        this.companyRepository = companyRepository;
        this.operationRepository = operationRepository;
        this.surveyResponseRepository = surveyResponseRepository;
        this.appUserRepository = appUserRepository;
        this.requestAuthContext = requestAuthContext;
        this.validator = validator;
        this.surveyQuestionAutoLexiconService = surveyQuestionAutoLexiconService;
    }

    @Override
    @Transactional
    public SurveyResponseDto createSurvey(UUID companyId, CreateSurveyRequest request) {
        validateRequest(request);

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Şirket bulunamadı: " + companyId));

        AppUser createdBy = resolveUserForCompany(companyId, request.getCreatedByUserId());

        Survey survey = new Survey();
        survey.setCompany(company);
        survey.setCreatedBy(createdBy);
        applyCreateFields(survey, request);

        return toDto(surveyRepository.save(survey));
    }

    @Override
    @Transactional
    public SurveyResponseDto updateSurvey(UUID companyId, UUID surveyId, UpdateSurveyRequest request) {
        validateRequest(request);

        Survey survey = getSurveyOrThrow(companyId, surveyId);
        validateSurveyUpdateRules(survey, request);
        applyUpdateFields(survey, request);

        if (request.getStatus() == SurveyStatus.PUBLISHED) {
            refreshQuestionAutoLexicons(survey);
            validateReadyForPublishing(survey);
        }

        return toDto(surveyRepository.save(survey));
    }

    @Override
    @Transactional
    public void deleteSurvey(UUID companyId, UUID surveyId) {
        Survey survey = getSurveyOrThrow(companyId, surveyId);

        if (survey.getStatus() != SurveyStatus.DRAFT) {
            throw new ValidationException("Yalnızca taslak anketler silinebilir.");
        }
        if (operationRepository.existsBySurvey_IdAndDeletedAtIsNull(surveyId)) {
            throw new ValidationException("Bu anketi kullanan operasyonlar olduğu için anket silinemez.");
        }
        if (surveyResponseRepository.existsBySurvey_IdAndDeletedAtIsNull(surveyId)) {
            throw new ValidationException("Bu ankete verilmiş yanıtlar olduğu için anket silinemez.");
        }

        OffsetDateTime deletedAt = OffsetDateTime.now();
        for (SurveyQuestion question : surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(surveyId)) {
            for (SurveyQuestionOption option : surveyQuestionOptionRepository
                    .findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(question.getId())) {
                option.setDeletedAt(deletedAt);
                surveyQuestionOptionRepository.save(option);
            }
            question.setDeletedAt(deletedAt);
            surveyQuestionRepository.save(question);
        }
        survey.setDeletedAt(deletedAt);
        surveyRepository.save(survey);
    }

    @Override
    public SurveyResponseDto getSurveyById(UUID companyId, UUID surveyId) {
        return toDto(getSurveyOrThrow(companyId, surveyId));
    }

    @Override
    public List<SurveyResponseDto> listSurveysByCompany(UUID companyId) {
        ensureCompanyExists(companyId);

        return surveyRepository.findAllByCompany_IdAndDeletedAtIsNull(companyId).stream()
                .sorted(Comparator.comparing(Survey::getCreatedAt).reversed())
                .map(this::toDto)
                .toList();
    }

    private void validateRequest(CreateSurveyRequest request) {
        Set<ConstraintViolation<CreateSurveyRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations.iterator().next().getMessage());
        }
    }

    private void validateRequest(UpdateSurveyRequest request) {
        Set<ConstraintViolation<UpdateSurveyRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations.iterator().next().getMessage());
        }
    }

    private void ensureCompanyExists(UUID companyId) {
        if (!companyRepository.existsById(companyId)) {
            throw new NotFoundException("Şirket bulunamadı: " + companyId);
        }
    }

    private Survey getSurveyOrThrow(UUID companyId, UUID surveyId) {
        return surveyRepository.findByIdAndCompany_IdAndDeletedAtIsNull(surveyId, companyId)
                .orElseThrow(() -> new NotFoundException("Bu şirkette anket bulunamadı: " + surveyId));
    }

    private AppUser resolveUserForCompany(UUID companyId, UUID userId) {
        if (userId == null) {
            AppUser authenticatedUser = requestAuthContext.requireUser();
            return authenticatedUser.getCompany().getId().equals(companyId) ? authenticatedUser : null;
        }

        return appUserRepository.findById(userId)
                .filter(user -> user.getCompany().getId().equals(companyId))
                .orElseThrow(() -> new ValidationException("Oluşturan kullanıcı bu şirkete ait değil."));
    }

    private void applyCreateFields(Survey survey, CreateSurveyRequest request) {
        survey.setName(requireTrimmed(request.getName(), "Anket adı zorunludur."));
        survey.setDescription(trimToNull(request.getDescription()));
        survey.setStatus(SurveyStatus.DRAFT);
        survey.setLanguageCode(requireTrimmed(request.getLanguageCode(), "Dil kodu zorunludur."));
        survey.setIntroPrompt(trimToNull(request.getIntroPrompt()));
        survey.setClosingPrompt(trimToNull(request.getClosingPrompt()));
        survey.setMaxRetryPerQuestion(request.getMaxRetryPerQuestion() != null ? request.getMaxRetryPerQuestion() : 2);
        survey.setSourceProvider(trimToNull(request.getSourceProvider()));
        survey.setSourceExternalId(trimToNull(request.getSourceExternalId()));
        survey.setSourceFileName(trimToNull(request.getSourceFileName()));
        survey.setSourcePayloadJson(trimToNull(request.getSourcePayloadJson()));
    }

    private void applyUpdateFields(Survey survey, UpdateSurveyRequest request) {
        survey.setName(requireTrimmed(request.getName(), "Anket adı zorunludur."));
        survey.setDescription(trimToNull(request.getDescription()));
        survey.setLanguageCode(requireTrimmed(request.getLanguageCode(), "Dil kodu zorunludur."));
        survey.setIntroPrompt(trimToNull(request.getIntroPrompt()));
        survey.setClosingPrompt(trimToNull(request.getClosingPrompt()));
        survey.setMaxRetryPerQuestion(request.getMaxRetryPerQuestion());
        survey.setStatus(request.getStatus());
        survey.setSourceProvider(trimToNull(request.getSourceProvider()));
        survey.setSourceExternalId(trimToNull(request.getSourceExternalId()));
        survey.setSourceFileName(trimToNull(request.getSourceFileName()));
        survey.setSourcePayloadJson(trimToNull(request.getSourcePayloadJson()));
    }

    private void validateSurveyUpdateRules(Survey survey, UpdateSurveyRequest request) {
        SurveyStatus currentStatus = survey.getStatus();
        SurveyStatus requestedStatus = request.getStatus();

        if (currentStatus == SurveyStatus.ARCHIVED) {
            throw new ValidationException("Arşivlenmiş anketler değiştirilemez.");
        }

        if (currentStatus == SurveyStatus.PUBLISHED) {
            if (requestedStatus != SurveyStatus.ARCHIVED) {
                throw new ValidationException("Yayınlanmış bir anket yalnızca arşivlenebilir.");
            }
            if (!trimmedEquals(survey.getName(), request.getName())
                    || !trimmedEquals(survey.getDescription(), request.getDescription())
                    || !trimmedEquals(survey.getLanguageCode(), request.getLanguageCode())
                    || !trimmedEquals(survey.getIntroPrompt(), request.getIntroPrompt())
                    || !trimmedEquals(survey.getClosingPrompt(), request.getClosingPrompt())
                    || !survey.getMaxRetryPerQuestion().equals(request.getMaxRetryPerQuestion())
                    || !trimmedEquals(survey.getSourceProvider(), request.getSourceProvider())
                    || !trimmedEquals(survey.getSourceExternalId(), request.getSourceExternalId())
                    || !trimmedEquals(survey.getSourceFileName(), request.getSourceFileName())
                    || !trimmedEquals(survey.getSourcePayloadJson(), request.getSourcePayloadJson())) {
                throw new ValidationException("Yayınlanmış bir anketin içeriği ve tekrar deneme ayarları değiştirilemez.");
            }
        }
    }

    private void validateReadyForPublishing(Survey survey) {
        List<SurveyQuestion> questions = surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(
                survey.getId()
        );
        if (questions.isEmpty()) {
            throw new ValidationException("Anket yayınlanmadan önce en az bir soru içermeli.");
        }

        for (SurveyQuestion question : questions) {
            if (isChoiceQuestion(question.getQuestionType())) {
                long activeOptionCount = surveyQuestionOptionRepository
                        .findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(question.getId())
                        .stream()
                        .filter(SurveyQuestionOption::isActive)
                        .count();
                if (activeOptionCount < 2) {
                    throw new ValidationException(
                            "Anket yayınlanmadan önce seçimli sorularda en az iki aktif seçenek bulunmalı."
                    );
                }
            }
        }
    }

    private void refreshQuestionAutoLexicons(Survey survey) {
        List<SurveyQuestion> questions = surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(
                survey.getId()
        );
        for (SurveyQuestion question : questions) {
            List<SurveyQuestionOption> options = surveyQuestionOptionRepository
                    .findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(question.getId());
            question.setSettingsJson(surveyQuestionAutoLexiconService.rebuildSettingsJson(survey, question, options));
            surveyQuestionRepository.save(question);
        }
    }

    private SurveyResponseDto toDto(Survey survey) {
        return new SurveyResponseDto(
                survey.getId(),
                survey.getCompany().getId(),
                survey.getName(),
                survey.getDescription(),
                survey.getStatus(),
                survey.getLanguageCode(),
                survey.getIntroPrompt(),
                survey.getClosingPrompt(),
                survey.getMaxRetryPerQuestion(),
                survey.getSourceProvider(),
                survey.getSourceExternalId(),
                survey.getSourceFileName(),
                survey.getSourcePayloadJson(),
                survey.getCreatedBy() != null ? survey.getCreatedBy().getId() : null,
                survey.getCreatedAt(),
                survey.getUpdatedAt()
        );
    }

    private boolean isChoiceQuestion(QuestionType questionType) {
        return questionType == QuestionType.SINGLE_CHOICE || questionType == QuestionType.MULTI_CHOICE;
    }

    private boolean trimmedEquals(String left, String right) {
        String leftTrimmed = trimToNull(left);
        String rightTrimmed = trimToNull(right);
        if (leftTrimmed == null) {
            return rightTrimmed == null;
        }
        return leftTrimmed.equals(rightTrimmed);
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
