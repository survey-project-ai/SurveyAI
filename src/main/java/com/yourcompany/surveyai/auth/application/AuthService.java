package com.yourcompany.surveyai.auth.application;

import com.yourcompany.surveyai.auth.api.dto.AuthenticatedUserResponse;
import com.yourcompany.surveyai.auth.api.dto.LoginRequest;
import com.yourcompany.surveyai.auth.domain.entity.AuthSession;
import com.yourcompany.surveyai.auth.repository.AuthSessionRepository;
import com.yourcompany.surveyai.common.domain.entity.AppUser;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.domain.enums.AppUserStatus;
import com.yourcompany.surveyai.common.exception.UnauthorizedException;
import com.yourcompany.surveyai.common.repository.AppUserRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    public static final long SESSION_MAX_AGE_SECONDS = 60L * 60L * 24L * 14L;

    private final AppUserRepository appUserRepository;
    private final AuthSessionRepository authSessionRepository;
    private final PasswordHashService passwordHashService;

    public AuthService(
            AppUserRepository appUserRepository,
            AuthSessionRepository authSessionRepository,
            PasswordHashService passwordHashService
    ) {
        this.appUserRepository = appUserRepository;
        this.authSessionRepository = authSessionRepository;
        this.passwordHashService = passwordHashService;
    }

    @Transactional
    public LoginResult login(LoginRequest request) {
        AppUser user = appUserRepository.findAllByEmailIgnoreCaseAndDeletedAtIsNull(request.getEmail().trim()).stream()
                .filter(candidate -> candidate.getStatus() == AppUserStatus.ACTIVE)
                .min(Comparator.comparing(candidate -> candidate.getCompany().getCreatedAt()))
                .orElseThrow(() -> new UnauthorizedException("E-posta veya şifre hatalı."));

        if (!passwordHashService.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("E-posta veya şifre hatalı.");
        }

        String rawSessionToken = UUID.randomUUID() + "." + UUID.randomUUID();
        AuthSession session = new AuthSession();
        session.setAppUser(user);
        session.setSessionTokenHash(passwordHashService.sha256(rawSessionToken));
        session.setLastSeenAt(OffsetDateTime.now());
        session.setExpiresAt(OffsetDateTime.now().plusSeconds(SESSION_MAX_AGE_SECONDS));
        authSessionRepository.save(session);

        user.setLastLoginAt(OffsetDateTime.now());
        appUserRepository.save(user);

        return new LoginResult(rawSessionToken, toAuthenticatedUserResponse(user));
    }

    public AuthenticatedUserResponse getCurrentUser(AppUser user) {
        AppUser hydratedUser = appUserRepository.findByIdAndDeletedAtIsNull(user.getId())
                .orElseThrow(() -> new UnauthorizedException("Oturum açmanız gerekiyor."));
        return toAuthenticatedUserResponse(hydratedUser);
    }

    @Transactional
    public Optional<AppUser> resolveAuthenticatedUser(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            return Optional.empty();
        }

        return authSessionRepository.findBySessionTokenHashAndDeletedAtIsNull(passwordHashService.sha256(rawSessionToken))
                .filter(session -> session.getExpiresAt().isAfter(OffsetDateTime.now()))
                .map(session -> {
                    session.setLastSeenAt(OffsetDateTime.now());
                    AppUser user = authSessionRepository.save(session).getAppUser();
                    // Initialize the company relation before storing the user in request scope.
                    user.getCompany().getId();
                    return user;
                })
                .filter(user -> user.getStatus() == AppUserStatus.ACTIVE && user.getDeletedAt() == null);
    }

    @Transactional
    public void logout(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            return;
        }

        authSessionRepository.findBySessionTokenHashAndDeletedAtIsNull(passwordHashService.sha256(rawSessionToken))
                .ifPresent(authSessionRepository::delete);
    }

    private AuthenticatedUserResponse toAuthenticatedUserResponse(AppUser user) {
        Company company = user.getCompany();
        Map<String, Object> metadata = company.getMetadataJson() == null ? Map.of() : company.getMetadataJson();
        String fullName = StreamNameHelper.fullName(user.getFirstName(), user.getLastName(), user.getEmail());

        return new AuthenticatedUserResponse(
                company.getId(),
                new AuthenticatedUserResponse.CompanySummary(
                        company.getId(),
                        company.getName(),
                        company.getSlug(),
                        company.getTimezone(),
                        company.getStatus().name(),
                        metadata
                ),
                new AuthenticatedUserResponse.UserSummary(
                        user.getId(),
                        user.getEmail(),
                        user.getFirstName(),
                        user.getLastName(),
                        fullName,
                        user.getRole().name(),
                        user.getStatus().name(),
                        user.getLastLoginAt()
                )
        );
    }

    public record LoginResult(String sessionToken, AuthenticatedUserResponse user) {
    }

    private static final class StreamNameHelper {
        private static String fullName(String firstName, String lastName, String email) {
            String combined = ((firstName == null ? "" : firstName.trim()) + " " + (lastName == null ? "" : lastName.trim())).trim();
            return combined.isBlank() ? email : combined;
        }
    }
}
