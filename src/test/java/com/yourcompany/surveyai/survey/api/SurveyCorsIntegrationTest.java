package com.yourcompany.surveyai.survey.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yourcompany.surveyai.auth.api.AuthInterceptor;
import com.yourcompany.surveyai.auth.application.AuthCookieService;
import com.yourcompany.surveyai.auth.application.AuthService;
import com.yourcompany.surveyai.auth.config.AuthWebMvcConfigurer;
import com.yourcompany.surveyai.common.api.GlobalExceptionHandler;
import com.yourcompany.surveyai.common.config.CorsConfig;
import com.yourcompany.surveyai.common.domain.entity.AppUser;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.domain.enums.AppUserRole;
import com.yourcompany.surveyai.common.domain.enums.AppUserStatus;
import com.yourcompany.surveyai.common.domain.enums.CompanyStatus;
import com.yourcompany.surveyai.operation.application.service.ImportedSurveyOperationService;
import com.yourcompany.surveyai.survey.application.dto.response.SurveyResponseDto;
import com.yourcompany.surveyai.survey.application.service.GoogleFormsImportService;
import com.yourcompany.surveyai.survey.application.service.SurveyService;
import com.yourcompany.surveyai.survey.domain.enums.SurveyStatus;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SurveyController.class)
@Import({CorsConfig.class, AuthWebMvcConfigurer.class, AuthInterceptor.class, GlobalExceptionHandler.class})
class SurveyCorsIntegrationTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:3000";
    private static final UUID FOREIGN_COMPANY_ID = UUID.fromString("f4fa1991-3fb3-3236-97eb-076f69115099");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SurveyService surveyService;

    @MockBean
    private GoogleFormsImportService googleFormsImportService;

    @MockBean
    private ImportedSurveyOperationService importedSurveyOperationService;

    @Autowired
    private TestAuthService authService;

    @Test
    void preflightForCreateSurveyIsHandledWithoutAuthentication() throws Exception {
        UUID companyId = UUID.randomUUID();

        mockMvc.perform(options("/api/v1/companies/{companyId}/surveys", companyId)
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, org.hamcrest.Matchers.containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers.containsString("content-type")));
    }

    @Test
    void preflightForPublishSurveyUpdateIsHandledWithoutAuthentication() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID surveyId = UUID.randomUUID();

        mockMvc.perform(options("/api/v1/companies/{companyId}/surveys/{surveyId}", companyId, surveyId)
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, org.hamcrest.Matchers.containsString("PUT")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers.containsString("content-type")));
    }

    @Test
    void preflightFromUnlistedRailwayOriginIsRejectedByDefault() throws Exception {
        UUID companyId = UUID.randomUUID();

        mockMvc.perform(options("/api/v1/companies/{companyId}/surveys", companyId)
                        .header(HttpHeaders.ORIGIN, "https://attacker-site.up.railway.app")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void createSurveyRemainsProtectedAndSucceedsWhenSessionIsResolved() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID surveyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AppUser user = authenticatedUser(companyId, userId);

        authService.setResolvedUser(Optional.of(user));
        when(surveyService.createSurvey(eq(companyId), any())).thenReturn(surveyResponse(companyId, surveyId, userId, SurveyStatus.DRAFT));

        mockMvc.perform(post("/api/v1/companies/{companyId}/surveys", companyId)
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .cookie(new Cookie(AuthCookieService.SESSION_COOKIE_NAME, "session-token"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "Draft survey",
                                  "description": "Save from builder",
                                  "languageCode": "tr",
                                  "introPrompt": "Welcome",
                                  "closingPrompt": "Thanks",
                                  "maxRetryPerQuestion": 2,
                                  "createdByUserId": "%s"
                                }
                                """.formatted(userId)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        verify(surveyService).createSurvey(eq(companyId), any());
    }

    @Test
    void publishSurveyUpdateRemainsProtectedAndSucceedsWhenSessionIsResolved() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID surveyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AppUser user = authenticatedUser(companyId, userId);

        authService.setResolvedUser(Optional.of(user));
        when(surveyService.updateSurvey(eq(companyId), eq(surveyId), any()))
                .thenReturn(surveyResponse(companyId, surveyId, userId, SurveyStatus.PUBLISHED));

        mockMvc.perform(put("/api/v1/companies/{companyId}/surveys/{surveyId}", companyId, surveyId)
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .cookie(new Cookie(AuthCookieService.SESSION_COOKIE_NAME, "session-token"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "Live survey",
                                  "description": "Publish from builder",
                                  "languageCode": "tr",
                                  "introPrompt": "Welcome",
                                  "closingPrompt": "Thanks",
                                  "maxRetryPerQuestion": 2,
                                  "status": "PUBLISHED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        verify(surveyService).updateSurvey(eq(companyId), eq(surveyId), any());
    }

    @Test
    void createSurveyReturnsForbiddenWhenAuthenticatedCompanyDoesNotMatchRequestCompany() throws Exception {
        UUID authenticatedCompanyId = UUID.randomUUID();
        UUID requestedCompanyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AppUser user = authenticatedUser(authenticatedCompanyId, userId);

        authService.setResolvedUser(Optional.of(user));

        mockMvc.perform(post("/api/v1/companies/{companyId}/surveys", requestedCompanyId)
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .cookie(new Cookie(AuthCookieService.SESSION_COOKIE_NAME, "session-token"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "Draft survey",
                                  "description": "Save from builder",
                                  "languageCode": "tr",
                                  "introPrompt": "Welcome",
                                  "closingPrompt": "Thanks",
                                  "maxRetryPerQuestion": 2,
                                  "createdByUserId": "%s"
                                }
                                """.formatted(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void listSurveysReturnsForbiddenWhenForeignCompanyPathSegmentCarriesPathParameter() throws Exception {
        UUID authenticatedCompanyId = UUID.randomUUID();
        authService.setResolvedUser(Optional.of(authenticatedUser(authenticatedCompanyId, UUID.randomUUID())));

        mockMvc.perform(get("/api/v1/companies/{companyId};x/surveys", FOREIGN_COMPANY_ID)
                        .cookie(new Cookie(AuthCookieService.SESSION_COOKIE_NAME, "session-token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(surveyService, never()).listSurveysByCompany(any());
    }

    @Test
    void listSurveysReturnsForbiddenWhenForeignCompanyPathSegmentIsPercentEncoded() throws Exception {
        UUID authenticatedCompanyId = UUID.randomUUID();
        authService.setResolvedUser(Optional.of(authenticatedUser(authenticatedCompanyId, UUID.randomUUID())));
        String encodedForeignCompanyId = "%66" + FOREIGN_COMPANY_ID.toString().substring(1);

        mockMvc.perform(get(URI.create("/api/v1/companies/" + encodedForeignCompanyId + "/surveys"))
                        .cookie(new Cookie(AuthCookieService.SESSION_COOKIE_NAME, "session-token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(surveyService, never()).listSurveysByCompany(any());
    }

    @Test
    void createSurveyReturnsBadRequestForInvalidCompanyIdQueryParameter() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AppUser user = authenticatedUser(companyId, userId);

        authService.setResolvedUser(Optional.of(user));

        mockMvc.perform(post("/api/v1/companies/{companyId}/surveys", companyId)
                        .queryParam("companyId", "not-a-uuid")
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .cookie(new Cookie(AuthCookieService.SESSION_COOKIE_NAME, "session-token"))
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "Draft survey",
                                  "description": "Save from builder",
                                  "languageCode": "tr",
                                  "introPrompt": "Welcome",
                                  "closingPrompt": "Thanks",
                                  "maxRetryPerQuestion": 2,
                                  "createdByUserId": "%s"
                                }
                                """.formatted(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid company ID"));
    }

    private static AppUser authenticatedUser(UUID companyId, UUID userId) {
        Company company = new Company();
        company.setId(companyId);
        company.setName("SurveyAI");
        company.setSlug("surveyai");
        company.setStatus(CompanyStatus.ACTIVE);
        company.setTimezone("Europe/Istanbul");
        company.setMetadataJson(Map.of());

        AppUser user = new AppUser();
        user.setId(userId);
        user.setCompany(company);
        user.setEmail("user@example.com");
        user.setFirstName("Dev");
        user.setLastName("User");
        user.setRole(AppUserRole.ADMIN);
        user.setStatus(AppUserStatus.ACTIVE);
        return user;
    }

    private static SurveyResponseDto surveyResponse(UUID companyId, UUID surveyId, UUID userId, SurveyStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new SurveyResponseDto(
                surveyId,
                companyId,
                "Customer feedback",
                "Builder flow",
                status,
                "tr",
                "Welcome",
                "Thanks",
                2,
                null,
                null,
                null,
                null,
                userId,
                now,
                now
        );
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        AuthCookieService authCookieService() {
            return new AuthCookieService();
        }

        @Bean
        TestAuthService authService() {
            return new TestAuthService();
        }

        @Bean
        Environment environment() {
            return new StandardEnvironment();
        }
    }

    static class TestAuthService extends AuthService {

        private Optional<AppUser> resolvedUser = Optional.empty();

        TestAuthService() {
            super(null, null, null);
        }

        void setResolvedUser(Optional<AppUser> resolvedUser) {
            this.resolvedUser = resolvedUser;
        }

        @Override
        public Optional<AppUser> resolveAuthenticatedUser(String rawSessionToken) {
            return resolvedUser;
        }
    }
}
