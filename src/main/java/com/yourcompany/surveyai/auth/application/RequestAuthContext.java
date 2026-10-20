package com.yourcompany.surveyai.auth.application;

import com.yourcompany.surveyai.common.domain.entity.AppUser;
import com.yourcompany.surveyai.common.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class RequestAuthContext {

    public static final String REQUEST_USER_ATTRIBUTE = RequestAuthContext.class.getName() + ".user";

    private final HttpServletRequest request;

    public RequestAuthContext(HttpServletRequest request) {
        this.request = request;
    }

    public AppUser requireUser() {
        Object value = request.getAttribute(REQUEST_USER_ATTRIBUTE);
        if (value instanceof AppUser user) {
            return user;
        }
        throw new UnauthorizedException("Oturum açmanız gerekiyor.");
    }

    public UUID requireCompanyId() {
        return requireUser().getCompany().getId();
    }
}
