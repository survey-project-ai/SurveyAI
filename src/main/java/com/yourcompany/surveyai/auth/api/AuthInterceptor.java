package com.yourcompany.surveyai.auth.api;

import com.yourcompany.surveyai.auth.application.AuthCookieService;
import com.yourcompany.surveyai.auth.application.AuthService;
import com.yourcompany.surveyai.auth.application.RequestAuthContext;
import com.yourcompany.surveyai.common.domain.entity.AppUser;
import com.yourcompany.surveyai.common.exception.CompanyIsolationException;
import com.yourcompany.surveyai.common.exception.UnauthorizedException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final AuthCookieService authCookieService;

    public AuthInterceptor(AuthService authService, AuthCookieService authCookieService) {
        this.authService = authService;
        this.authCookieService = authCookieService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        if ("/api/v1/auth/login".equals(path)) {
            return true;
        }

        AppUser user = authService.resolveAuthenticatedUser(authCookieService.extractSessionToken(request))
                .orElseThrow(() -> new UnauthorizedException("Oturum açmanız gerekiyor."));

        enforceCompanyScope(request, user);
        request.setAttribute(RequestAuthContext.REQUEST_USER_ATTRIBUTE, user);
        return true;
    }

    private void enforceCompanyScope(HttpServletRequest request, AppUser user) {
        UUID authenticatedCompanyId = user.getCompany().getId();
        String companyIdParam = request.getParameter("companyId");

        if (companyIdParam != null && !authenticatedCompanyId.equals(parseCompanyId(companyIdParam))) {
            throw new CompanyIsolationException("Bu şirketin verilerine erişim yetkiniz yok.");
        }

        // Check the {companyId} value Spring MVC binds to the controller. Matching the raw request URI
        // misses variants of the same segment such as ";x" path parameters or percent-encoded characters.
        String pathCompanyId = resolveCompanyIdUriVariable(request);
        if (pathCompanyId != null && !authenticatedCompanyId.equals(parseCompanyId(pathCompanyId))) {
            throw new CompanyIsolationException("Bu şirketin verilerine erişim yetkiniz yok.");
        }
    }

    private String resolveCompanyIdUriVariable(HttpServletRequest request) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> uriVariables) {
            Object companyId = uriVariables.get("companyId");
            return companyId == null ? null : companyId.toString();
        }
        return null;
    }

    private UUID parseCompanyId(String rawCompanyId) {
        try {
            return UUID.fromString(rawCompanyId);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("Geçersiz şirket kimliği.");
        }
    }
}
