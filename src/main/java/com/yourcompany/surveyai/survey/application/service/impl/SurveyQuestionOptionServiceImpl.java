package com.yourcompany.surveyai.survey.application.service.impl;

import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.common.repository.CompanyRepository;
import com.yourcompany.surveyai.response.repository.SurveyAnswerRepository;
import com.yourcompany.surveyai.survey.application.dto.request.CreateSurveyQuestionOptionRequest;
import com.yourcompany.surveyai.survey.application.dto.request.UpdateSurveyQuestionOptionRequest;
import com.yourcompany.surveyai.survey.application.dto.response.SurveyQuestionOptionResponseDto;
import com.yourcompany.surveyai.survey.application.service.SurveyQuestionOptionService;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SurveyQuestionOptionServiceImpl implements SurveyQuestionOptionService {

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository surveyQuestionRepository;
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository;
    private final SurveyAnswerRepository surveyAnswerRepository;
    private final CompanyRepository companyRepository;
    private final Validator validator;
    private final SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService;

    public SurveyQuestionOptionServiceImpl(
            SurveyRepository surveyRepository,
            SurveyQuestionRepository surveyQuestionRepository,
            SurveyQuestionOptionRepository surveyQuestionOptionRepository,
            SurveyAnswerRepository surveyAnswerRepository,
            CompanyRepository companyRepository,
            Validator validator,
            SurveyQuestionAutoLexiconService surveyQuestionAutoLexiconService
    ) {
        this.surveyRepository = surveyRepository;
        this.surveyQuestionRepository = surveyQuestionRepository;
        this.surveyQuestionOptionRepository = surveyQuestionOptionRepository;
        this.surveyAnswerRepository = surveyAnswerRepository;
        this.companyRepository = companyRepository;
        this.validator = validator;
        this.surveyQuestionAutoLexiconService = surveyQuestionAutoLexiconService;
    }

    @Override
    @Transactional
    public SurveyQuestionOptionResponseDto addOption(
            UUID companyId,
            UUID surveyId,
            UUID questionId,
            CreateSurveyQuestionOptionRequest request
    ) {
        validateRequest(request);

        SurveyQuestion question = getChoiceQuestion(companyId, surveyId, questionId, true);
        ensureOptionCodeAvailable(questionId, request.getOptionCode(), null);
        ensureOptionOrderAvailable(questionId, request.getOptionOrder(), null);

        SurveyQuestionOption option = new SurveyQuestionOption();
        option.setCompany(question.getCompany());
        option.setSurveyQuestion(question);
        applyOptionFields(option, request);
        SurveyQuestionOption savedOption = surveyQuestionOptionRepository.save(option);
        refreshQuestionAutoLexicon(questionId);
        return toDto(savedOption);
    }

    @Override
    @Transactional
    public SurveyQuestionOptionResponseDto updateOption(
            UUID companyId,
            UUID surveyId,
            UUID questionId,
            UUID optionId,
            UpdateSurveyQuestionOptionRequest request
    ) {
        validateRequest(request);

        SurveyQuestionOption option = getOption(companyId, surveyId, questionId, optionId);
        ensureDraftSurvey(option.getSurveyQuestion().getSurvey());
        ensureChoiceQuestion(option.getSurveyQuestion());
        ensureOptionCodeAvailable(questionId, request.getOptionCode(), optionId);
        ensureOptionOrderAvailable(questionId, request.getOptionOrder(), optionId);

        applyOptionFields(option, request);
        SurveyQuestionOption savedOption = surveyQuestionOptionRepository.save(option);
        refreshQuestionAutoLexicon(questionId);
        return toDto(savedOption);
    }

    @Override
    @Transactional
    public void deleteOption(UUID companyId, UUID surveyId, UUID questionId, UUID optionId) {
        SurveyQuestionOption option = getOption(companyId, surveyId, questionId, optionId);
        ensureDraftSurvey(option.getSurveyQuestion().getSurvey());
        ensureChoiceQuestion(option.getSurveyQuestion());

        if (surveyAnswerRepository.existsBySelectedOption_IdAndDeletedAtIsNull(optionId)) {
            throw new ValidationException("Bu seçeneği kullanan cevaplar olduğu için seçenek silinemez.");
        }

        surveyQuestionOptionRepository.delete(option);
        refreshQuestionAutoLexicon(questionId);
    }

    @Override
    public List<SurveyQuestionOptionResponseDto> listOptions(UUID companyId, UUID surveyId, UUID questionId) {
        SurveyQuestion question = getChoiceQuestion(companyId, surveyId, questionId, false);
        return surveyQuestionOptionRepository.findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(question.getId())
                .stream()
                .map(this::toDto)
                .toList();
    }

    private void validateRequest(CreateSurveyQuestionOptionRequest request) {
        Set<ConstraintViolation<CreateSurveyQuestionOptionRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations.iterator().next().getMessage());
        }
    }

    private void validateRequest(UpdateSurveyQuestionOptionRequest request) {
        Set<ConstraintViolation<UpdateSurveyQuestionOptionRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations.iterator().next().getMessage());
        }
    }

    private SurveyQuestion getChoiceQuestion(UUID companyId, UUID surveyId, UUID questionId, boolean requireDraft) {
        SurveyQuestion question = getQuestion(companyId, surveyId, questionId);
        if (requireDraft) {
            ensureDraftSurvey(question.getSurvey());
        }
        ensureChoiceQuestion(question);
        return question;
    }

    private SurveyQuestion getQuestion(UUID companyId, UUID surveyId, UUID questionId) {
        ensureSurveyExists(companyId, surveyId);
        return surveyQuestionRepository.findByIdAndSurvey_IdAndCompany_IdAndDeletedAtIsNull(questionId, surveyId, companyId)
                .orElseThrow(() -> new NotFoundException("Anket sorusu bulunamadı: " + questionId));
    }

    private SurveyQuestionOption getOption(UUID companyId, UUID surveyId, UUID questionId, UUID optionId) {
        getQuestion(companyId, surveyId, questionId);
        return surveyQuestionOptionRepository
                .findByIdAndSurveyQuestion_IdAndSurveyQuestion_Survey_IdAndSurveyQuestion_Company_IdAndDeletedAtIsNull(
                        optionId,
                        questionId,
                        surveyId,
                        companyId
                )
                .orElseThrow(() -> new NotFoundException("Soru seçeneği bulunamadı: " + optionId));
    }

    private Survey ensureSurveyExists(UUID companyId, UUID surveyId) {
        if (!companyRepository.existsById(companyId)) {
            throw new NotFoundException("Şirket bulunamadı: " + companyId);
        }

        return surveyRepository.findByIdAndCompany_IdAndDeletedAtIsNull(surveyId, companyId)
                .orElseThrow(() -> new NotFoundException("Bu şirkette anket bulunamadı: " + surveyId));
    }

    private void ensureDraftSurvey(Survey survey) {
        if (survey.getStatus() != SurveyStatus.DRAFT) {
            throw new ValidationException("Soru seçenekleri yalnızca anket taslak durumundayken değiştirilebilir.");
        }
    }

    private void ensureChoiceQuestion(SurveyQuestion question) {
        QuestionType type = question.getQuestionType();
        if (type != QuestionType.SINGLE_CHOICE && type != QuestionType.MULTI_CHOICE) {
            throw new ValidationException("Seçenekler yalnızca seçimli sorularda kullanılabilir.");
        }
    }

    private void ensureOptionCodeAvailable(UUID questionId, String optionCode, UUID currentOptionId) {
        surveyQuestionOptionRepository.findBySurveyQuestion_IdAndOptionCodeAndDeletedAtIsNull(
                questionId,
                requireTrimmed(optionCode, "Seçenek kodu zorunludur.")
        ).ifPresent(existing -> {
            if (!existing.getId().equals(currentOptionId)) {
                throw new ValidationException("Bu soruda aynı seçenek kodu zaten var.");
            }
        });
    }

    private void ensureOptionOrderAvailable(UUID questionId, Integer optionOrder, UUID currentOptionId) {
        surveyQuestionOptionRepository.findBySurveyQuestion_IdAndOptionOrderAndDeletedAtIsNull(questionId, optionOrder)
                .ifPresent(existing -> {
                    if (!existing.getId().equals(currentOptionId)) {
                        throw new ValidationException("Bu soruda aynı seçenek sırası zaten var.");
                    }
                });
    }

    private void applyOptionFields(SurveyQuestionOption option, CreateSurveyQuestionOptionRequest request) {
        option.setOptionOrder(request.getOptionOrder());
        option.setOptionCode(requireTrimmed(request.getOptionCode(), "Seçenek kodu zorunludur."));
        option.setLabel(requireTrimmed(request.getLabel(), "Seçenek etiketi zorunludur."));
        option.setValue(requireTrimmed(request.getValue(), "Seçenek değeri zorunludur."));
        option.setActive(request.isActive());
    }

    private void applyOptionFields(SurveyQuestionOption option, UpdateSurveyQuestionOptionRequest request) {
        option.setOptionOrder(request.getOptionOrder());
        option.setOptionCode(requireTrimmed(request.getOptionCode(), "Seçenek kodu zorunludur."));
        option.setLabel(requireTrimmed(request.getLabel(), "Seçenek etiketi zorunludur."));
        option.setValue(requireTrimmed(request.getValue(), "Seçenek değeri zorunludur."));
        option.setActive(request.isActive());
    }

    private SurveyQuestionOptionResponseDto toDto(SurveyQuestionOption option) {
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

    private void refreshQuestionAutoLexicon(UUID questionId) {
        surveyQuestionRepository.findById(questionId).ifPresent(question -> {
            List<SurveyQuestionOption> options = surveyQuestionOptionRepository
                    .findAllBySurveyQuestion_IdAndDeletedAtIsNullOrderByOptionOrderAsc(questionId);
            question.setSettingsJson(surveyQuestionAutoLexiconService.rebuildSettingsJson(question, options));
            surveyQuestionRepository.save(question);
        });
    }

    private String requireTrimmed(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new ValidationException(message);
        }
        return value.trim();
    }
}
