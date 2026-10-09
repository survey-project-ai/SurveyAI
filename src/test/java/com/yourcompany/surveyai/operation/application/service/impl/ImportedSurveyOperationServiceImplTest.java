package com.yourcompany.surveyai.operation.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.auth.application.RequestAuthContext;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.domain.entity.AppUser;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.repository.CompanyRepository;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.response.domain.entity.SurveyAnswer;
import com.yourcompany.surveyai.response.domain.entity.SurveyResponse;
import com.yourcompany.surveyai.response.repository.SurveyAnswerRepository;
import com.yourcompany.surveyai.response.repository.SurveyResponseRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.domain.enums.SurveyStatus;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionOptionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class ImportedSurveyOperationServiceImplTest {

    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final SurveyRepository surveyRepository = mock(SurveyRepository.class);
    private final SurveyQuestionRepository surveyQuestionRepository = mock(SurveyQuestionRepository.class);
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository = mock(SurveyQuestionOptionRepository.class);
    private final OperationRepository operationRepository = mock(OperationRepository.class);
    private final OperationContactRepository operationContactRepository = mock(OperationContactRepository.class);
    private final CallJobRepository callJobRepository = mock(CallJobRepository.class);
    private final CallAttemptRepository callAttemptRepository = mock(CallAttemptRepository.class);
    private final SurveyResponseRepository surveyResponseRepository = mock(SurveyResponseRepository.class);
    private final SurveyAnswerRepository surveyAnswerRepository = mock(SurveyAnswerRepository.class);
    private final RequestAuthContext requestAuthContext = mock(RequestAuthContext.class);

    private final ImportedSurveyOperationServiceImpl service = new ImportedSurveyOperationServiceImpl(
            companyRepository,
            surveyRepository,
            surveyQuestionRepository,
            surveyQuestionOptionRepository,
            operationRepository,
            operationContactRepository,
            callJobRepository,
            callAttemptRepository,
            surveyResponseRepository,
            surveyAnswerRepository,
            requestAuthContext,
            new ObjectMapper()
    );

    @Test
    void importCompletedSurvey_leavesProviderCallIdEmptySoRepeatedImportsDoNotCollide() {
        Company company = new Company();
        company.setId(UUID.randomUUID());

        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setCompany(company);
        survey.setName("Memnuniyet");
        survey.setStatus(SurveyStatus.PUBLISHED);

        SurveyQuestion question = new SurveyQuestion();
        question.setId(UUID.randomUUID());
        question.setSurvey(survey);
        question.setCode("q1");
        question.setTitle("Neden?");
        question.setQuestionType(QuestionType.OPEN_ENDED);

        AppUser user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setCompany(company);

        when(companyRepository.findById(company.getId())).thenReturn(Optional.of(company));
        when(surveyRepository.findByIdAndCompany_IdAndDeletedAtIsNull(survey.getId(), company.getId())).thenReturn(Optional.of(survey));
        when(surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(survey.getId())).thenReturn(List.of(question));
        when(surveyQuestionOptionRepository.findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(anyCollection()))
                .thenReturn(List.of());
        when(requestAuthContext.requireUser()).thenReturn(user);
        when(operationRepository.save(any(Operation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(operationContactRepository.save(any(OperationContact.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(callJobRepository.save(any(CallJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(callAttemptRepository.save(any(CallAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(surveyResponseRepository.save(any(SurveyResponse.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(surveyAnswerRepository.save(any(SurveyAnswer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sonuclar.csv",
                "text/csv",
                "q1\nFiyatlar uygun\nPersonel ilgili\n".getBytes(StandardCharsets.UTF_8)
        );

        service.importCompletedSurvey(company.getId(), survey.getId(), file, "Saha importu");

        // call_attempt has a global UNIQUE (provider, provider_call_id). A synthetic id such as
        // "import-import-row-2" collided with every later import that had no respondent id column.
        ArgumentCaptor<CallAttempt> attempts = ArgumentCaptor.forClass(CallAttempt.class);
        verify(callAttemptRepository, times(2)).save(attempts.capture());
        assertThat(attempts.getAllValues()).allSatisfy(attempt -> assertThat(attempt.getProviderCallId()).isNull());
    }
}
