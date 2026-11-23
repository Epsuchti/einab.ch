package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.FoodsharingApiSession;
import ch.it4user.foodsharing.domain.entity.FoodsharingBezirkMessageConnection;
import ch.it4user.foodsharing.repository.FoodsharingApiSessionRepository;
import ch.it4user.foodsharing.repository.FoodsharingBezirkMessageConnectionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
public class FoodsharingClient {
    private static final Logger log = LoggerFactory.getLogger(FoodsharingClient.class);
    private static final ZoneId SWISS_ZONE = ZoneId.of("Europe/Zurich");
    private static final DateTimeFormatter USER_INFO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter PICKUP_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);
    private final RestClient restClient;
    private final RestClient userInfoRestClient;
    private final AppProperties appProperties;
    private final FoodsharingApiSessionRepository sessionRepository;
    private final FoodsharingBezirkMessageConnectionRepository bezirkMessageConnectionRepository;
    private final CryptoService cryptoService;
    private final OutboundMessageGuard outboundGuard;
    private final Map<String, String> accountUserIds = new java.util.concurrent.ConcurrentHashMap<>();
    public static final String FALLBACK_ACCOUNT_KEY = "fallback";
    /** profile-settings and verifications are bot-only on Foodsharing: after a 403 they are not asked again for a while. */
    private static final java.time.Duration BOT_ONLY_RETRY_AFTER = java.time.Duration.ofHours(1);
    private final Map<String, Instant> botOnlyForbiddenUntil = new java.util.concurrent.ConcurrentHashMap<>();
    /** Own short transaction for storing re-login sessions, so they never collide with a long automation transaction. */
    private final TransactionTemplate sessionTransaction;
    /** Re-login sessions of messaging connections by id; the entity itself is not changed (see {@link #authenticate(FoodsharingBezirkMessageConnection)}). */
    private final Map<java.util.UUID, RefreshedSession> refreshedConnectionSessions = new java.util.concurrent.ConcurrentHashMap<>();

    private record RefreshedSession(SessionData session, Instant authenticatedAt) {}

    public FoodsharingClient(AppProperties appProperties,
                             FoodsharingApiSessionRepository sessionRepository,
                             FoodsharingBezirkMessageConnectionRepository bezirkMessageConnectionRepository,
                             CryptoService cryptoService,
                             PlatformTransactionManager transactionManager,
                             OutboundMessageGuard outboundGuard) {
        this.outboundGuard = outboundGuard;
        this.sessionTransaction = new TransactionTemplate(transactionManager);
        this.sessionTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.appProperties = appProperties;
        this.sessionRepository = sessionRepository;
        this.bezirkMessageConnectionRepository = bezirkMessageConnectionRepository;
        this.cryptoService = cryptoService;
        this.restClient = RestClient.builder().baseUrl(appProperties.getFoodsharing().getBaseUrl()).build();
        this.userInfoRestClient = RestClient.builder()
                .baseUrl(appProperties.getFoodsharing().getPhoneNumberApiBaseUrl())
                .build();
    }

    public FoodsharingUserInfo getUser(String foodsharingId, Bezirk bezirk) {
        if (DemoAccounts.isDemoId(foodsharingId)) {
            return DemoAccounts.userInfo(foodsharingId);
        }
        Optional<FoodsharingBezirkMessageConnection> connection = configuredConnection(bezirk);
        Map<?, ?> body = connection.isPresent()
                ? authenticatedGet(connection.get(), "/api/users/{userId}", Map.class, false, foodsharingId)
                : authenticatedGet("/api/users/{userId}", foodsharingId, false);
        Object id = body.get("id");
        Object name = body.get("name");
        Object sleeping = body.get("isSleeping");
        String resolvedFoodsharingId = String.valueOf(id == null ? foodsharingId : id);
        FoodsharingUserInfo userInfo = fetchUserInfo(resolvedFoodsharingId, bezirk);
        return new FoodsharingUserInfo(resolvedFoodsharingId, String.valueOf(name == null ? foodsharingId : name), userInfo.phoneNumber(), Boolean.TRUE.equals(sleeping), userInfo.verificationDate(),
                userInfo.verified(), userInfo.lastVerificationDate(), userInfo.verificationLookupFailed());
    }

    public String fetchPhoneNumber(String foodsharingId, Bezirk bezirk) {
        return fetchUserInfo(foodsharingId, bezirk).phoneNumber();
    }

    public FoodsharingUserInfo fetchUserInfo(String foodsharingId, Bezirk bezirk) {
        if (DemoAccounts.isDemoId(foodsharingId)) {
            return DemoAccounts.userInfo(foodsharingId);
        }
        Optional<FoodsharingBezirkMessageConnection> connection = configuredConnection(bezirk);
        // Bezirke without a bot account (Zürich) get phone number and verification dates from the user-info API,
        // even when a normal messaging account is connected: that account may not call the bot-only endpoints.
        if (usesLegacyUserInfoApi(bezirk) && hasLegacyUserInfoToken()) {
            return fetchLegacyUserInfo(foodsharingId);
        }
        if (connection.isEmpty()) {
            if (usesLegacyUserInfoApi(bezirk)) {
                return fetchLegacyUserInfo(foodsharingId);
            }
            if (bezirk != null) {
                log.warn("Could not fetch foodsharing user info for user={}: no admin connection configured for bezirk={}",
                        foodsharingId, bezirk.getSlug());
            }
            return FoodsharingUserInfo.lookupFailed(foodsharingId);
        }

        String phoneNumber = null;
        Instant verificationDate = null;
        Boolean verified = null;
        Instant lastVerificationDate = null;
        boolean verificationLookupFailed = false;
        String accountKey = accountKey(connection);
        if (!botOnlyForbidden(accountKey, "profile-settings")) {
            try {
                Map<?, ?> profileSettings = authenticatedGet(connection.get(),
                        "/api/users/{userId}/profile-settings", Map.class, false, foodsharingId);
                phoneNumber = firstNonBlank(profileSettings, "mobile", "phone");
            } catch (RuntimeException ex) {
                rememberBotOnlyForbidden(accountKey, "profile-settings", ex);
                log.warn("Could not fetch foodsharing profile settings for user={}: {}", foodsharingId, ex.getMessage());
            }
        }
        if (botOnlyForbidden(accountKey, "verifications")) {
            return new FoodsharingUserInfo(foodsharingId, null, phoneNumber, false, null, null, null, true);
        }
        try {
            Object response = authenticatedGet(connection.get(),
                    "/api/users/{userId}/verifications", Object.class, false, foodsharingId);
            List<VerificationEvent> events = verificationEntries(response).stream()
                    .map(entry -> new VerificationEvent(
                            Boolean.TRUE.equals(entry.get("wasVerified")),
                            parseVerificationDate(first(entry, "date", "verifiedAt", "createdAt"), foodsharingId)))
                    .filter(event -> event.date() != null)
                    .toList();
            verificationDate = events.stream()
                    .filter(VerificationEvent::wasVerified)
                    .map(VerificationEvent::date)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            lastVerificationDate = events.stream()
                    .filter(VerificationEvent::wasVerified)
                    .map(VerificationEvent::date)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            // The newest entry tells whether the user is verified right now (a verification can be revoked).
            verified = events.stream()
                    .max(Comparator.comparing(VerificationEvent::date))
                    .map(VerificationEvent::wasVerified)
                    .orElse(null);
        } catch (RuntimeException ex) {
            rememberBotOnlyForbidden(accountKey, "verifications", ex);
            log.warn("Could not fetch foodsharing verifications for user={}: {}", foodsharingId, ex.getMessage());
            verificationLookupFailed = true;
        }
        return new FoodsharingUserInfo(foodsharingId, null, phoneNumber, false, verificationDate, verified, lastVerificationDate,
                verificationLookupFailed);
    }

    private boolean neverVerified(Map<?, ?> response) {
        if (response == null) {
            return false;
        }
        return isUnknownVerification(stringValue(response.get("firstVerification")))
                || isUnknownVerification(stringValue(first(response, "lastVerification", "lastVerificationDate")));
    }

    private boolean hasLegacyUserInfoToken() {
        String token = appProperties.getFoodsharing().getPhoneNumberApiToken();
        return token != null && !token.isBlank();
    }

    private boolean botOnlyForbidden(String accountKey, String endpoint) {
        Instant until = botOnlyForbiddenUntil.get(accountKey + "|" + endpoint);
        return until != null && Instant.now().isBefore(until);
    }

    private void rememberBotOnlyForbidden(String accountKey, String endpoint, RuntimeException ex) {
        if (ex instanceof HttpClientErrorException http && http.getStatusCode().value() == 403) {
            if (botOnlyForbiddenUntil.put(accountKey + "|" + endpoint, Instant.now().plus(BOT_ONLY_RETRY_AFTER)) == null) {
                log.info("Account {} may not call /{}: skipping it for {}", accountKey, endpoint, BOT_ONLY_RETRY_AFTER);
            }
        }
    }

    private FoodsharingUserInfo fetchLegacyUserInfo(String foodsharingId) {
        String token = appProperties.getFoodsharing().getPhoneNumberApiToken();
        if (token == null || token.isBlank()) {
            log.warn("Could not fetch foodsharing user info for Zürich user={}: phone-number API token is not configured",
                    foodsharingId);
            return FoodsharingUserInfo.lookupFailed(foodsharingId);
        }
        try {
            log.debug("Foodsharing Zürich user-info request: GET /{}/info", foodsharingId);
            Map<?, ?> response = userInfoRestClient.get()
                    .uri("/{foodsharingId}/info", foodsharingId)
                    .header("X-API-Token", token)
                    .retrieve()
                    .body(Map.class);
            Instant firstVerification = parseVerificationDate(
                    response == null ? null : response.get("firstVerification"), foodsharingId);
            Instant lastVerification = parseVerificationDate(
                    response == null ? null : first(response, "lastVerification", "lastVerificationDate"), foodsharingId);
            return new FoodsharingUserInfo(
                    foodsharingId,
                    null,
                    stringValue(response == null ? null : response.get("number")),
                    false,
                    firstVerification,
                    // This API only reports verification dates. "unknown" is its answer for a user that was never
                    // verified; an absent date leaves the state unknown.
                    firstVerification != null || lastVerification != null ? Boolean.TRUE
                            : neverVerified(response) ? Boolean.FALSE : null,
                    lastVerification != null ? lastVerification : firstVerification);
        } catch (RuntimeException ex) {
            log.warn("Could not fetch foodsharing Zürich user info for user={}: {}", foodsharingId, ex.getMessage());
            return FoodsharingUserInfo.lookupFailed(foodsharingId);
        }
    }

    /** The user-info API answers "unknown" for a user that was never verified. */
    private static boolean isUnknownVerification(String value) {
        return value != null && value.equalsIgnoreCase("unknown");
    }

    private Instant parseVerificationDate(Object value, String foodsharingId) {
        String normalized = stringValue(value);
        if (normalized == null) {
            return null;
        }
        if (isUnknownVerification(normalized)) {
            return null;
        }
        try {
            return LocalDateTime.parse(normalized, USER_INFO_DATE_TIME).atZone(SWISS_ZONE).toInstant();
        } catch (DateTimeParseException ex) {
            try {
                return Instant.parse(normalized);
            } catch (DateTimeParseException ignoredInstant) {
                try {
                    return OffsetDateTime.parse(normalized).toInstant();
                } catch (DateTimeParseException ignoredOffset) {
                    log.warn("Could not parse foodsharing verification date for user={}: {}", foodsharingId, normalized);
                    return null;
                }
            }
        }
    }

    private String firstNonBlank(Map<?, ?> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            String value = stringValue(values.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private List<Map<?, ?>> verificationEntries(Object response) {
        Object entries = response instanceof Map<?, ?> map
                ? first(map, "verifications", "items", "data")
                : response;
        if (!(entries instanceof List<?> list)) {
            return List.of();
        }
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                result.add(map);
            }
        }
        return result;
    }

    private Object first(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private boolean isConfigured(FoodsharingBezirkMessageConnection connection) {
        return connection.getFoodsharingEmail() != null && !connection.getFoodsharingEmail().isBlank()
                && connection.getFoodsharingPasswordCiphertext() != null
                && !connection.getFoodsharingPasswordCiphertext().isBlank();
    }

    private Optional<FoodsharingBezirkMessageConnection> configuredConnection(Bezirk bezirk) {
        return bezirk == null
                ? Optional.empty()
                : bezirkMessageConnectionRepository.findByBezirk(bezirk).filter(this::isConfigured);
    }

    /** Whether user info (e.g. verification dates for the cleaning rule) can be fetched for users of this Bezirk. */
    public boolean hasUserInfoSource(Bezirk bezirk) {
        if (configuredConnection(bezirk).isPresent()) {
            return true;
        }
        String token = appProperties.getFoodsharing().getPhoneNumberApiToken();
        return usesLegacyUserInfoApi(bezirk) && token != null && !token.isBlank();
    }

    private boolean usesLegacyUserInfoApi(Bezirk bezirk) {
        return bezirk != null && bezirk.isUseFoodsharingUserInfoApi() && "zuerich".equals(bezirk.getSlug());
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() || normalized.equalsIgnoreCase("null") ? null : normalized;
    }

    public void sendMessage(String foodsharingId, String body) {
        if (skipDemoAccount(foodsharingId)) return;
        if (!outboundGuard.allowFoodsharingUser(foodsharingId, body)) return;
        Map<?, ?> lookup = authenticatedPost("/api/conversations/lookup", Map.of("ids", List.of(Long.valueOf(foodsharingId))), false);
        Object conversationId = Optional.ofNullable(lookup.get("id")).orElse(lookup.get("conversationId"));
        if (conversationId == null) throw new IllegalStateException("Foodsharing conversation lookup did not return an id");
        authenticatedPost("/api/conversations/{conversationId}/messages", Map.of("body", body), false, conversationId);
    }

    public void sendMessage(FoodsharingBezirkMessageConnection connection, String foodsharingId, String body) {
        if (skipDemoAccount(foodsharingId)) return;
        if (!outboundGuard.allowFoodsharingUser(foodsharingId, body)) return;
        Map<?, ?> lookup = authenticatedPost(connection, "/api/conversations/lookup",
                Map.of("ids", List.of(Long.valueOf(foodsharingId))), false);
        Object conversationId = Optional.ofNullable(lookup.get("id")).orElse(lookup.get("conversationId"));
        if (conversationId == null) {
            throw new IllegalStateException("Foodsharing conversation lookup did not return an id");
        }
        authenticatedPost(connection, "/api/conversations/{conversationId}/messages",
                Map.of("body", body), false, conversationId);
    }

    /** Demo accounts have no Foodsharing profile, so nothing is ever sent to them on Foodsharing. */
    private static boolean skipDemoAccount(String foodsharingId) {
        if (!DemoAccounts.isDemoId(foodsharingId)) return false;
        log.info("Foodsharing message to demo account {} skipped", foodsharingId);
        return true;
    }

    public void configureBezirkMessageConnection(FoodsharingBezirkMessageConnection connection,
                                                  String email,
                                                  String password) {
        SessionData sessionData = login(email, password);
        connection.setFoodsharingEmail(email);
        connection.setFoodsharingPasswordCiphertext(cryptoService.encrypt(password));
        connection.setSessionCookieCiphertext(cryptoService.encrypt(sessionData.cookie()));
        connection.setCsrfTokenCiphertext(cryptoService.encrypt(sessionData.csrfToken()));
        connection.setAuthenticatedAt(Instant.now());
        if (sessionData.userId() != null) {
            connection.setFoodsharingUserId(sessionData.userId());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> authenticatedGet(String path, Object uriVariable, boolean retried) {
        SessionData session = session();
        try {
            logRequest("GET", path, session, uriVariable);
            return restClient.get().uri(path, uriVariable).headers(h -> applySession(h, session)).retrieve().body(Map.class);
        } catch (HttpClientErrorException ex) {
            if (shouldReauthenticate(ex, retried)) { authenticate(); return authenticatedGet(path, uriVariable, true); }
            throw ex;
        }
    }

    private <T> T authenticatedGet(FoodsharingBezirkMessageConnection connection,
                                   String path,
                                   Class<T> responseType,
                                   boolean retried,
                                   Object... uriVariables) {
        SessionData session = session(connection);
        try {
            logRequest("GET", path, connection.getFoodsharingEmail(), session, uriVariables);
            return restClient.get().uri(path, uriVariables)
                    .headers(headers -> applySession(headers, session))
                    .retrieve()
                    .body(responseType);
        } catch (HttpClientErrorException ex) {
            if (shouldReauthenticate(ex, retried)) {
                authenticate(connection);
                return authenticatedGet(connection, path, responseType, true, uriVariables);
            }
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> authenticatedPost(String path, Object request, boolean retried, Object... uriVariables) {
        SessionData session = session();
        try {
            logRequest("POST", path, session, uriVariables);
            return restClient.post().uri(path, uriVariables).contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> applySession(h, session)).body(request).retrieve().body(Map.class);
        } catch (HttpClientErrorException ex) {
            if (shouldReauthenticate(ex, retried)) { authenticate(); return authenticatedPost(path, request, true, uriVariables); }
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> authenticatedPost(FoodsharingBezirkMessageConnection connection,
                                        String path,
                                        Object request,
                                        boolean retried,
                                        Object... uriVariables) {
        SessionData session = session(connection);
        try {
            logRequest("POST", path, connection.getFoodsharingEmail(), session, uriVariables);
            return restClient.post().uri(path, uriVariables).contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> applySession(h, session)).body(request).retrieve().body(Map.class);
        } catch (HttpClientErrorException ex) {
            if (shouldReauthenticate(ex, retried)) {
                authenticate(connection);
                return authenticatedPost(connection, path, request, true, uriVariables);
            }
            throw ex;
        }
    }

    /**
     * Identifies the Foodsharing account used for a Bezirk: its admin (message) connection when configured,
     * otherwise the fallback account from {@code app.foodsharing.admin-user}.
     */
    public String accountKey(Bezirk bezirk) {
        return accountKey(configuredConnection(bezirk));
    }

    /** Account key of a Bezirk message connection, or of the fallback account when empty. */
    public static String accountKey(Optional<FoodsharingBezirkMessageConnection> connection) {
        return connection.map(value -> "bezirk:" + value.getId()).orElse(FALLBACK_ACCOUNT_KEY);
    }

    /**
     * Foodsharing user ID of the sending account: the given Bezirk message connection, or the fallback account
     * when empty. Accounts configured before the ID was stored are logged in once to learn it.
     */
    public String accountFoodsharingUserId(Optional<FoodsharingBezirkMessageConnection> connection) {
        String email = connection.map(FoodsharingBezirkMessageConnection::getFoodsharingEmail)
                .orElse(appProperties.getFoodsharing().getAdminUser());
        String cacheKey = accountKey(connection) + "|" + (email == null ? "" : email.toLowerCase(java.util.Locale.ROOT));
        String cached = accountUserIds.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String userId;
        if (connection.isPresent()) {
            FoodsharingBezirkMessageConnection value = connection.get();
            userId = stringValue(value.getFoodsharingUserId());
            if (userId == null) {
                SessionData sessionData = authenticate(value);
                userId = stringValue(sessionData.userId());
            }
        } else {
            userId = sessionRepository.findFirstByOrderByAuthenticatedAtDesc()
                    .map(FoodsharingApiSession::getFoodsharingUserId)
                    .map(this::stringValue)
                    .orElse(null);
            if (userId == null) {
                userId = stringValue(authenticate().userId());
            }
        }
        if (userId != null) {
            accountUserIds.put(cacheKey, userId);
        } else {
            log.warn("Foodsharing login for account {} did not return a user id; self-messages cannot be detected.", email);
        }
        return userId;
    }

    /** Stores of the Bezirk admin (or fallback) account. */
    public List<FoodsharingPickupModels.Store> stores(Bezirk bezirk) {
        return FoodsharingPickupApiClient.parseStores(
                exchange(bezirk, "GET", "/api/users/current/stores?excludeInactive=true", null, Object.class, false));
    }

    /** Upcoming pickups of a store, read with the Bezirk admin (or fallback) account. */
    public List<FoodsharingPickupModels.Pickup> pickups(Bezirk bezirk, long storeId) {
        return FoodsharingPickupApiClient.parsePickups(storeId,
                exchange(bezirk, "GET", "/api/stores/{storeId}/pickups", null, Object.class, false, storeId));
    }

    /** Changes the number of slots and the description of a pickup. The account must be responsible for the store. */
    public void updatePickupSlots(Bezirk bezirk, long storeId, Instant pickupDate, int totalSlots, String description) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("totalSlots", totalSlots);
        body.put("description", description == null ? "" : description);
        exchange(bezirk, "PUT", "/api/stores/{storeId}/pickups/{pickupDate}", body, Void.class, false, storeId, formatPickupDate(pickupDate));
    }

    private <T> T exchange(Bezirk bezirk, String method, String path, Object body, Class<T> responseType, boolean retried, Object... uriVariables) {
        Optional<FoodsharingBezirkMessageConnection> connection = configuredConnection(bezirk);
        SessionData session = connection.map(this::session).orElseGet(this::session);
        String email = connection.map(FoodsharingBezirkMessageConnection::getFoodsharingEmail).orElse(appProperties.getFoodsharing().getAdminUser());
        try {
            logRequest(method, path, email, session, uriVariables);
            var request = restClient.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path, uriVariables)
                    .headers(headers -> applySession(headers, session));
            if (body != null) {
                request = request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            if (responseType == Void.class) {
                request.retrieve().toBodilessEntity();
                return null;
            }
            return request.retrieve().body(responseType);
        } catch (HttpClientErrorException ex) {
            if (shouldReauthenticate(ex, retried)) {
                if (connection.isPresent()) {
                    authenticate(connection.get());
                } else {
                    authenticate();
                }
                return exchange(bezirk, method, path, body, responseType, true, uriVariables);
            }
            throw ex;
        }
    }

    private static String formatPickupDate(Instant instant) {
        return PICKUP_DATE_FORMAT.format(instant);
    }

    private boolean shouldReauthenticate(HttpClientErrorException ex, boolean retried) {
        if (retried) {
            return false;
        }
        if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            return true;
        }
        return ex.getStatusCode() == HttpStatus.BAD_REQUEST
                && ex.getResponseBodyAsString() != null
                && ex.getResponseBodyAsString().contains("CSRF Failed");
    }

    private void applySession(HttpHeaders headers, SessionData session) {
        if (session.cookie() != null && !session.cookie().isBlank()) headers.set(HttpHeaders.COOKIE, session.cookie());
        if (session.csrfToken() != null && !session.csrfToken().isBlank()) headers.set("X-CSRF-Token", session.csrfToken());
    }

    private SessionData session() {
        return sessionRepository.findFirstByOrderByAuthenticatedAtDesc()
                .map(s -> new SessionData(cryptoService.decrypt(s.getSessionCookieCiphertext()), cryptoService.decrypt(s.getCsrfTokenCiphertext()), s.getFoodsharingUserId()))
                .orElseGet(this::authenticate);
    }

    private SessionData session(FoodsharingBezirkMessageConnection connection) {
        RefreshedSession refreshed = connection.getId() == null ? null : refreshedConnectionSessions.get(connection.getId());
        if (refreshed != null && (connection.getAuthenticatedAt() == null || refreshed.authenticatedAt().isAfter(connection.getAuthenticatedAt()))) {
            return refreshed.session();
        }
        String cookie = cryptoService.decrypt(connection.getSessionCookieCiphertext());
        if (cookie == null || cookie.isBlank()) {
            return authenticate(connection);
        }
        return new SessionData(cookie, cryptoService.decrypt(connection.getCsrfTokenCiphertext()), connection.getFoodsharingUserId());
    }

    @Transactional
    public SessionData authenticate() {
        if (appProperties.getFoodsharing().getAdminUser() == null || appProperties.getFoodsharing().getAdminUser().isBlank()
                || appProperties.getFoodsharing().getAdminPassword() == null || appProperties.getFoodsharing().getAdminPassword().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Foodsharing admin user and password are required."));
        }
        SessionData sessionData = loginWithStoredCredentials(
                appProperties.getFoodsharing().getAdminUser(),
                appProperties.getFoodsharing().getAdminPassword());
        try {
            // Own transaction and a bulk delete (no version check): two re-logins at the same time must not fail each other
            // or the transaction of the caller (e.g. an automation run).
            sessionTransaction.executeWithoutResult(status -> {
                sessionRepository.deleteAllInBatch();
                FoodsharingApiSession session = new FoodsharingApiSession();
                session.setSessionCookieCiphertext(cryptoService.encrypt(sessionData.cookie()));
                session.setCsrfTokenCiphertext(cryptoService.encrypt(sessionData.csrfToken()));
                session.setAuthenticatedAt(Instant.now());
                session.setFoodsharingUserId(sessionData.userId());
                sessionRepository.save(session);
            });
        } catch (RuntimeException ex) {
            log.warn("Foodsharing API session could not be stored; the next request logs in again.", ex);
        }
        return sessionData;
    }

    private SessionData authenticate(FoodsharingBezirkMessageConnection connection) {
        String password = cryptoService.decrypt(connection.getFoodsharingPasswordCiphertext());
        if (connection.getFoodsharingEmail() == null || connection.getFoodsharingEmail().isBlank()
                || password == null || password.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Foodsharing messaging connection cannot be refreshed. Configure its credentials again."));
        }
        // The entity is not changed: it may be held by a long transaction (e.g. an automation run), and a dirty copy
        // there would fail its version check at commit. The session is kept in memory and stored by id instead.
        SessionData sessionData = loginWithStoredCredentials(connection.getFoodsharingEmail(), password);
        if (sessionData.userId() == null) {
            sessionData = new SessionData(sessionData.cookie(), sessionData.csrfToken(), connection.getFoodsharingUserId());
        }
        Instant authenticatedAt = Instant.now();
        SessionData refreshed = sessionData;
        if (connection.getId() == null) {
            return refreshed;
        }
        refreshedConnectionSessions.put(connection.getId(), new RefreshedSession(refreshed, authenticatedAt));
        try {
            sessionTransaction.executeWithoutResult(status -> bezirkMessageConnectionRepository.updateSession(connection.getId(),
                    cryptoService.encrypt(refreshed.cookie()), cryptoService.encrypt(refreshed.csrfToken()), refreshed.userId(), authenticatedAt));
        } catch (RuntimeException ex) {
            log.warn("Foodsharing messaging session for bezirk connection {} could not be stored.", connection.getId(), ex);
        }
        return refreshed;
    }

    /**
     * Logs in with credentials saved on the server (the fallback account or a Bezirk messaging account). A refused login
     * is a server-side problem, not a wrong entry of the person using the app, so it must not be reported to them as
     * "your email or password is wrong" (e.g. when they request a login link).
     */
    private SessionData loginWithStoredCredentials(String email, String password) {
        try {
            return login(email, password);
        } catch (ApiException exception) {
            if (exception.getCode() != ApiErrorCode.INVALID_FOODSHARING_CREDENTIALS) {
                throw exception;
            }
            log.error("Foodsharing refused the login of the saved messaging account {}. Its password may have changed; "
                    + "connect the account again in the admin settings.", email);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.FOODSHARING_ACCOUNT_LOGIN_FAILED);
        }
    }

    @SuppressWarnings("unchecked")
    private SessionData login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Foodsharing email and password are required."));
        }
        log.debug("Foodsharing request: POST /api/login user={}", email);
        try {
            var response = restClient.post().uri("/api/login").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", password, "rememberMe", true))
                    .retrieve().toEntity(Map.class);
            SessionData sessionData = extractSessionData(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE), response.getBody());
            if (sessionData.userId() == null) {
                // The login response does not contain the user id; ask Foodsharing for the current user instead.
                String userId = fetchCurrentUserId(email, sessionData);
                if (userId == null) {
                    log.warn("Foodsharing login for user={} did not return a user id.", email);
                } else {
                    sessionData = new SessionData(sessionData.cookie(), sessionData.csrfToken(), userId);
                }
            }
            log.debug("Foodsharing auth established user={} cookie={} csrf={}",
                    email,
                    fingerprint(sessionData.cookie()),
                    fingerprint(sessionData.csrfToken()));
            return sessionData;
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_FOODSHARING_CREDENTIALS);
        }
    }

    /** Reads the Foodsharing user id of the logged-in account from the current-user endpoints. */
    @SuppressWarnings("unchecked")
    private String fetchCurrentUserId(String email, SessionData session) {
        for (String path : List.of("/api/user/current", "/api/users/current", "/api/user/current/details")) {
            try {
                logRequest("GET", path, email, session);
                Map<?, ?> body = restClient.get().uri(path)
                        .headers(headers -> applySession(headers, session))
                        .retrieve()
                        .body(Map.class);
                String userId = FoodsharingPickupApiClient.extractUserId(body);
                if (userId != null) {
                    log.debug("Foodsharing user id of user={} is {} (from {})", email, userId, path);
                    return userId;
                }
            } catch (RuntimeException exception) {
                log.debug("Foodsharing current-user request {} for user={} failed: {}", path, email, exception.getMessage());
            }
        }
        return null;
    }

    private void logRequest(String method, String path, SessionData session, Object... uriVariables) {
        logRequest(method, path, appProperties.getFoodsharing().getAdminUser(), session, uriVariables);
    }

    private void logRequest(String method, String path, String email, SessionData session, Object... uriVariables) {
        String resolvedPath = path;
        for (Object uriVariable : uriVariables) {
            resolvedPath = resolvedPath.replaceFirst("\\{[^}]+\\}", String.valueOf(uriVariable));
        }
        log.debug("Foodsharing request: {} {} user={} cookie={} csrf={}",
                method,
                resolvedPath,
                email,
                fingerprint(session.cookie()),
                fingerprint(session.csrfToken()));
    }

    private SessionData extractSessionData(List<String> setCookies, Map<?, ?> responseBody) {
        List<String> cookiePairs = new ArrayList<>();
        String csrf = null;
        for (String setCookie : setCookies) {
            String pair = setCookie.split(";", 2)[0];
            cookiePairs.add(pair);
            if (pair.startsWith("FS_CSRF_TOKEN=")) {
                csrf = pair.substring("FS_CSRF_TOKEN=".length());
            }
        }
        if ((csrf == null || csrf.isBlank()) && responseBody != null && responseBody.get("csrfToken") != null) {
            csrf = String.valueOf(responseBody.get("csrfToken"));
        }
        return new SessionData(String.join("; ", cookiePairs), csrf, FoodsharingPickupApiClient.extractUserId(responseBody));
    }

    private static String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 6 && i < hash.length; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record SessionData(String cookie, String csrfToken, String userId) {}

    private record VerificationEvent(boolean wasVerified, Instant date) {}
}
