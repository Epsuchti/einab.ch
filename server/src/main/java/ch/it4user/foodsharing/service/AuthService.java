package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.AuthSession;
import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.LoginToken;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import ch.it4user.foodsharing.domain.enumtype.UserPermission;
import ch.it4user.foodsharing.openapi.model.AuthResponse;
import ch.it4user.foodsharing.openapi.model.BezirkResponse;
import ch.it4user.foodsharing.openapi.model.MessageResponse;
import ch.it4user.foodsharing.openapi.model.SessionInfoResponse;
import ch.it4user.foodsharing.repository.AuthSessionRepository;
import ch.it4user.foodsharing.repository.LoginTokenRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    public static final String PRIVACY_POLICY_VERSION = "2026-10-25";

    private final LoginTokenRepository loginTokenRepository;
    private final AuthSessionRepository authSessionRepository;
    private final PermissionResolutionService permissionResolutionService;
    private final TokenService tokenService;
    private final FoodsharingMessageService messageService;
    private final MessageTemplateService messageTemplateService;
    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final BookingUserService bookingUserService;
    private final FoodsharingClient foodsharingClient;
    private final BezirkService bezirkService;
    private final LoginRateLimiter loginRateLimiter;
    private final OnboardingService onboardingService;

    public AuthService(LoginTokenRepository loginTokenRepository,
                       AuthSessionRepository authSessionRepository,
                       PermissionResolutionService permissionResolutionService,
                       TokenService tokenService,
                       FoodsharingMessageService messageService,
                       MessageTemplateService messageTemplateService,
                       AppProperties appProperties,
                       UserRepository userRepository,
                       BookingUserService bookingUserService,
                       FoodsharingClient foodsharingClient,
                       BezirkService bezirkService,
                       LoginRateLimiter loginRateLimiter,
                       OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
        this.loginTokenRepository = loginTokenRepository;
        this.authSessionRepository = authSessionRepository;
        this.permissionResolutionService = permissionResolutionService;
        this.tokenService = tokenService;
        this.messageService = messageService;
        this.messageTemplateService = messageTemplateService;
        this.appProperties = appProperties;
        this.userRepository = userRepository;
        this.bookingUserService = bookingUserService;
        this.foodsharingClient = foodsharingClient;
        this.bezirkService = bezirkService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @Transactional
    public MessageResponse requestLogin(String bezirkSlug, String foodsharingId, String returnUrl, String clientIp) {
        Bezirk selectedBezirk = bezirkService.requireActive(bezirkSlug);
        // Demo accounts have no Foodsharing inbox; they are created and signed in by DemoLoginService (test server only).
        if (DemoAccounts.isDemoId(foodsharingId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ACCOUNT_NOT_FOUND);
        }
        loginRateLimiter.check(foodsharingId.trim(), clientIp);
        LoginTarget loginTarget = resolveLoginTarget(foodsharingId, selectedBezirk);
        String rawToken = tokenService.generateToken();
        LoginToken loginToken = new LoginToken();
        loginToken.setFoodsharingId(loginTarget.foodsharingId());
        loginToken.setTokenHash(tokenService.hash(rawToken));
        loginToken.setExpiresAt(Instant.now().plus(appProperties.getAuth().getLoginTokenValidityMinutes(), ChronoUnit.MINUTES));
        loginTokenRepository.save(loginToken);

        String loginLink = appProperties.getFrontend().getBaseUrl()
                + "/bezirke/" + loginTarget.bezirkSlug() + "/verify-login?token=" + rawToken
                + safeReturnUrl(returnUrl)
                        .map(url -> "&returnUrl=" + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8))
                        .orElse("");
        // No push copy: the sign-in link would show on the lock screen.
        messageService.sendWithoutPush(
                loginTarget.foodsharingId(),
                messageTemplateService.loginSubject(loginTarget.language()),
                messageTemplateService.loginBody(loginTarget.language(), loginLink));

        MessageResponse response = new MessageResponse();
        response.setMessage("LOGIN_LINK_SENT");
        response.setDeliveryTarget("Foodsharing ID " + loginTarget.foodsharingId());
        return response;
    }

    @Transactional
    public AuthResponse verifyToken(String token) {
        LoginToken loginToken = loginTokenRepository.findByTokenHash(tokenService.hash(token))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_LOGIN_TOKEN));
        if (loginToken.getUsedAt() != null || loginToken.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.LOGIN_TOKEN_EXPIRED_OR_USED);
        }

        loginToken.setUsedAt(Instant.now());
        AuthResponse response = createAuthResponse(loginToken.getFoodsharingId());
        userRepository.findByFoodsharingIdIgnoreCase(loginToken.getFoodsharingId())
                .ifPresent(user -> user.setLastLoginAt(Instant.now()));
        return response;
    }

    /** Session for a demo account that {@link DemoLoginService} just created; never used for real users. */
    @Transactional
    public AuthResponse createDemoSession(String foodsharingId) {
        if (!DemoAccounts.isDemoId(foodsharingId)) {
            throw new IllegalArgumentException("Not a demo account: " + foodsharingId);
        }
        AuthResponse response = createAuthResponse(foodsharingId);
        userRepository.findByFoodsharingIdIgnoreCase(foodsharingId)
                .ifPresent(user -> user.setLastLoginAt(Instant.now()));
        return response;
    }

    @Transactional
    public AuthResponse acceptPrivacyConsent(String foodsharingId, String version) {
        if (!PRIVACY_POLICY_VERSION.equals(version)) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.VALIDATION_FAILED);
        }
        User user = userRepository.findByFoodsharingIdIgnoreCase(foodsharingId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ACCOUNT_NOT_FOUND));
        user.setPrivacyConsentAt(Instant.now());
        user.setPrivacyConsentVersion(version);
        return createAuthResponse(foodsharingId);
    }

    @Transactional(readOnly = true)
    public SessionInfoResponse currentSession(String foodsharingId) {
        User user = userRepository.findByFoodsharingIdIgnoreCaseAndActiveTrue(foodsharingId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTHENTICATION_REQUIRED));
        SessionInfoResponse response = new SessionInfoResponse();
        response.setFoodsharingId(user.getFoodsharingId());
        response.setDisplayName(user.getName() == null ? user.getFoodsharingId() : user.getName());
        response.setBezirk(toBezirkResponse(user.getBezirk()));
        response.setPermissions(toApiPermissions(permissionResolutionService.resolvePermissions(user)));
        response.setPrivacyConsentRequired(onboardingService.isPrivacyConsentRequired(user));
        response.setPhoneNumberRequired(user.getPhoneNumber() == null || user.getPhoneNumber().isBlank());
        response.setBezirkInfoRequired(onboardingService.isBezirkInfoRequired(user));
        return response;
    }

    /** Second onboarding step: the user read the info page of their home Bezirk. Only after the privacy consent. */
    @Transactional
    public SessionInfoResponse acknowledgeBezirkInfo(String foodsharingId) {
        User user = userRepository.findByFoodsharingIdIgnoreCaseAndActiveTrue(foodsharingId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTHENTICATION_REQUIRED));
        if (onboardingService.isPrivacyConsentRequired(user)) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ONBOARDING_REQUIRED);
        }
        if (user.getBezirk() != null) {
            user.setBezirkInfoSeenBezirkId(user.getBezirk().getId());
            user.setBezirkInfoSeenAt(Instant.now());
        }
        return currentSession(foodsharingId);
    }

    private LoginTarget resolveLoginTarget(String foodsharingId, Bezirk selectedBezirk) {
        String normalizedFoodsharingId = foodsharingId.trim();
        User user = userRepository.findByFoodsharingIdIgnoreCaseAndActiveTrue(normalizedFoodsharingId)
                .map(existingUser -> refreshPhoneNumber(existingUser,
                        existingUser.getBezirk() == null || !existingUser.getBezirk().isActive()
                                ? selectedBezirk : existingUser.getBezirk()))
                .orElseGet(() -> bookingUserService.getOrCreate(normalizedFoodsharingId, LanguageCode.DE, selectedBezirk));
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ACCOUNT_DISABLED);
        }
        if (user.getBezirk() == null || !user.getBezirk().isActive()) {
            // No home Bezirk yet, or it was disabled: the Bezirk the person logs in from becomes their home.
            bookingUserService.assignToBezirk(user, selectedBezirk);
        }
        String targetBezirkSlug = user.getBezirk() == null ? selectedBezirk.getSlug() : user.getBezirk().getSlug();
        return new LoginTarget(user.getFoodsharingId(), user.getPreferredLanguage(), targetBezirkSlug);
    }

    /**
     * Only pages of this app (any Bezirk) are allowed as return target, so a login link can never forward to
     * another site (open redirect). The target page does its own access checks. Login pages are excluded. The extra
     * punctuation is what Angular leaves unescaped in query values (e.g. the store of a survey link).
     */
    static Optional<String> safeReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank() || returnUrl.length() > 500) {
            return Optional.empty();
        }
        String url = returnUrl.trim();
        if (!url.startsWith("/bezirke/") || url.contains("//") || url.contains("\\") || !url.matches("[A-Za-z0-9/_\\-.?=&%,:+@;$]+")) {
            return Optional.empty();
        }
        if (url.matches("/bezirke/[^/?]+/(login|verify-login)([/?].*)?")) {
            return Optional.empty();
        }
        return Optional.of(url);
    }

    private User refreshPhoneNumber(User user, Bezirk bezirk) {
        FoodsharingUserInfo userInfo = foodsharingClient.fetchUserInfo(
                user.getFoodsharingId(),
                bezirk);
        if (userInfo.phoneNumber() != null) {
            user.setPhoneNumber(userInfo.phoneNumber());
        }
        bookingUserService.applyVerification(user, userInfo);
        return user;
    }

    private AuthResponse createAuthResponse(String foodsharingId) {
        Set<UserPermission> permissions = permissionResolutionService.resolvePermissions(foodsharingId);
        User loginUser = userRepository.findByFoodsharingIdIgnoreCase(foodsharingId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ACCOUNT_NOT_FOUND));
        if (!loginUser.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ACCOUNT_DISABLED);
        }

        String rawAuthToken = tokenService.generateToken();
        AuthSession authSession = new AuthSession();
        authSession.setFoodsharingId(foodsharingId);
        authSession.setTokenHash(tokenService.hash(rawAuthToken));
        authSession.setPermissions(permissions.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        authSession.setExpiresAt(Instant.now().plus(appProperties.getAuth().getTokenValidityDays(), ChronoUnit.DAYS));
        authSessionRepository.save(authSession);

        String displayName = userRepository.findByFoodsharingIdIgnoreCase(foodsharingId)
                .map(User::getName)
                .orElse(foodsharingId);

        AuthResponse response = new AuthResponse();
        response.setAuthToken(rawAuthToken);
        response.setExpiresAt(OffsetDateTime.ofInstant(authSession.getExpiresAt(), ZoneOffset.UTC));
        response.setEmail(null);
        response.setFoodsharingId(authSession.getFoodsharingId());
        response.setDisplayName(displayName);
        response.setBezirk(toBezirkResponse(loginUser.getBezirk()));
        response.setPermissions(toApiPermissions(permissions));
        response.setPrivacyConsentRequired(onboardingService.isPrivacyConsentRequired(loginUser));
        response.setPhoneNumberRequired(loginUser.getPhoneNumber() == null || loginUser.getPhoneNumber().isBlank());
        response.setBezirkInfoRequired(onboardingService.isBezirkInfoRequired(loginUser));
        return response;
    }

    private static java.util.List<ch.it4user.foodsharing.openapi.model.UserPermission> toApiPermissions(Set<UserPermission> permissions) {
        return permissions.stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(permission -> ch.it4user.foodsharing.openapi.model.UserPermission.fromValue(permission.name()))
                .toList();
    }

    private BezirkResponse toBezirkResponse(Bezirk bezirk) {
        // A disabled home Bezirk is treated like none, so the app shows the Bezirk picker instead of a dead page.
        if (bezirk == null || !bezirk.isActive()) {
            return null;
        }
        BezirkResponse response = new BezirkResponse();
        response.setId(bezirk.getId());
        response.setName(bezirk.getName());
        response.setSlug(bezirk.getSlug());
        response.setInfoAvailable(bezirk.hasInfo());
        return response;
    }

    private record LoginTarget(String foodsharingId, LanguageCode language, String bezirkSlug) {
    }
}
