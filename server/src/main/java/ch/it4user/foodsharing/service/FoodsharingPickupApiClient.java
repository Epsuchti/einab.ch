package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.FoodsharingAdminConnection;
import ch.it4user.foodsharing.repository.FoodsharingAdminConnectionRepository;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Service
public class FoodsharingPickupApiClient {
    private static final Logger log = LoggerFactory.getLogger(FoodsharingPickupApiClient.class);
    private static final int MAX_GET_ATTEMPTS = 3;
    private static final long GET_RETRY_DELAY_MILLIS = 1_000L;
    private final RestClient restClient;
    private final OutboundMessageGuard outboundGuard;
    private final CryptoService cryptoService;
    private final FoodsharingAdminConnectionRepository connectionRepository;
    private final TransactionTemplate sessionTransaction;
    /**
     * Sessions from a re-login, by connection id. The connection entity is never changed on a re-login: it may be held by a
     * long automation transaction, and a dirty copy there would fail its version check at commit.
     */
    private final Map<java.util.UUID, RefreshedSession> refreshedSessions = new java.util.concurrent.ConcurrentHashMap<>();

    public FoodsharingPickupApiClient(AppProperties appProperties, CryptoService cryptoService, FoodsharingAdminConnectionRepository connectionRepository, PlatformTransactionManager transactionManager, OutboundMessageGuard outboundGuard) {
        this.outboundGuard = outboundGuard;
        this.cryptoService = cryptoService;
        this.connectionRepository = connectionRepository;
        this.sessionTransaction = new TransactionTemplate(transactionManager);
        this.sessionTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.restClient = RestClient.builder().baseUrl(appProperties.getFoodsharing().getBaseUrl()).build();
    }

    @SuppressWarnings("unchecked")
    public Session login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Foodsharing email and password are required."));
        }
        log.debug("Foodsharing request: POST /api/login user={}", email);
        org.springframework.http.ResponseEntity<Map> response;
        try {
            response = restClient.post().uri("/api/login").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", password, "rememberMe", true))
                    .retrieve().toEntity(Map.class);
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_FOODSHARING_CREDENTIALS);
        }
        String cookie = extractCookieHeader(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));
        Map<?, ?> body = response.getBody();
        String csrf = extractCsrfToken(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE), response.getHeaders().getFirst("X-CSRF-Token"), body);
        String userId = extractUserId(body);
        if (csrf == null || csrf.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.UNEXPECTED_ERROR, List.of("Foodsharing login did not return a CSRF token."));
        }
        log.debug("Foodsharing auth established user={} cookie={} csrf={}", email, fingerprint(cookie), fingerprint(csrf));
        return new Session(cookie, csrf, userId);
    }

    @SuppressWarnings("unchecked")
    public List<FoodsharingPickupModels.Store> stores(FoodsharingAdminConnection connection) {
        Object body = get(connection, "GET", "/api/users/current/stores?excludeInactive=true", Object.class, false);
        return parseStores(body);
    }

    /** Parses the response of {@code GET /api/users/current/stores}. */
    static List<FoodsharingPickupModels.Store> parseStores(Object body) {
        return asList(body).stream()
                .map(v -> (Map<?, ?>) v)
                .map(m -> new FoodsharingPickupModels.Store(
                        longValue(first(m, "id", "storeId")),
                        string(first(m, "name", "title")),
                        Boolean.TRUE.equals(first(m, "isManaging", "managing"))))
                .toList();
    }

    /** Region ids the account is Botschafter (region admin) of, read from {@code GET /api/users/current/details}. */
    public List<Long> ambassadorRegionIds(FoodsharingAdminConnection connection) {
        Object body = get(connection, "GET", "/api/users/current/details", Object.class, false);
        return parseAmbassadorRegionIds(body);
    }

    /** Parses {@code regions[]} of {@code GET /api/users/current/details}; {@code isResponsible} marks a Botschafter. */
    static List<Long> parseAmbassadorRegionIds(Object body) {
        if (!(body instanceof Map<?, ?> details)) return List.of();
        return asList(details.get("regions")).stream()
                .filter(v -> v instanceof Map<?, ?>)
                .map(v -> (Map<?, ?>) v)
                .filter(m -> Boolean.TRUE.equals(first(m, "isResponsible", "isAdmin")))
                .map(m -> longValue(m.get("id")))
                .filter(id -> id > 0)
                .distinct()
                .toList();
    }

    /**
     * Stores with an established cooperation that belong directly to the region (not to a subregion), read from
     * {@code GET /api/regions/{regionId}/stores}. These are the stores a Botschafter of the region may manage.
     */
    public List<FoodsharingPickupModels.Store> regionStores(FoodsharingAdminConnection connection, long regionId) {
        Object body = get(connection, "GET", "/api/regions/{regionId}/stores", Object.class, false, regionId);
        return parseRegionStores(regionId, body);
    }

    /** Parses the response of {@code GET /api/regions/{regionId}/stores}. */
    static List<FoodsharingPickupModels.Store> parseRegionStores(long regionId, Object body) {
        return asList(body).stream()
                .filter(v -> v instanceof Map<?, ?>)
                .map(v -> (Map<?, ?>) v)
                .filter(m -> !(m.get("region") instanceof Map<?, ?> region) || longValue(region.get("id")) == regionId)
                .filter(m -> isCooperationEstablished(m.get("cooperationStatus")))
                .map(m -> new FoodsharingPickupModels.Store(longValue(first(m, "id", "storeId")), string(first(m, "name", "title")), false))
                .filter(store -> store.id() > 0)
                .toList();
    }

    /** Foodsharing {@code CooperationStatus::COOPERATION_ESTABLISHED} is 5. */
    private static boolean isCooperationEstablished(Object status) {
        if (status instanceof Number n) return n.intValue() == 5;
        String value = string(status).trim();
        return value.equals("5") || value.equalsIgnoreCase("COOPERATION_ESTABLISHED");
    }

    /** True when the account may edit the store (manager, Botschafter of its region or orga), from {@code GET /api/stores/{storeId}/permissions}. */
    public boolean mayEditStore(FoodsharingAdminConnection connection, long storeId) {
        Object body = get(connection, "GET", "/api/stores/{storeId}/permissions", Object.class, false, storeId);
        return body instanceof Map<?, ?> permissions && Boolean.TRUE.equals(permissions.get("mayEditStore"));
    }

    @SuppressWarnings("unchecked")
    public List<FoodsharingPickupModels.Pickup> pickups(FoodsharingAdminConnection connection, long storeId) {
        Object body = get(connection, "GET", "/api/stores/{storeId}/pickups", Object.class, false, storeId);
        return parsePickups(storeId, body);
    }

    /** Parses the response of {@code GET /api/stores/{storeId}/pickups}. */
    static List<FoodsharingPickupModels.Pickup> parsePickups(long storeId, Object body) {
        List<FoodsharingPickupModels.Pickup> result = new ArrayList<>();
        for (Object item : asList(body)) {
            Map<?, ?> pickup = (Map<?, ?>) item;
            Instant date = instant(first(pickup, "date", "pickupDate"));
            List<FoodsharingPickupModels.PickupUser> users = new ArrayList<>();
            for (Object userSlot : asList(first(pickup, "occupiedSlots", "users"))) {
                Map<?, ?> slot = (Map<?, ?>) userSlot;
                Map<?, ?> profile = first(slot, "profile") instanceof Map<?, ?> p ? p : slot;
                users.add(new FoodsharingPickupModels.PickupUser(string(first(profile, "id", "userId")), string(first(profile, "name")), Boolean.TRUE.equals(first(slot, "isConfirmed", "confirmed")), instantOrNull(first(slot, "signUpDate"))));
            }
            result.add(new FoodsharingPickupModels.Pickup(storeId, date, intValue(first(pickup, "totalSlots", "slots")), users));
        }
        return result;
    }

    /**
     * Store log entries of the given action type (e.g. 12 = a foodsaver signed out of a pickup slot).
     * The endpoint is paged with an offset; pages are requested until an empty page is returned.
     */
    public List<FoodsharingPickupModels.StoreLogEntry> storeLog(FoodsharingAdminConnection connection, long storeId, Instant from, Instant to, int actionType) {
        List<FoodsharingPickupModels.StoreLogEntry> result = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < 20; page++) {
            Object body = get(connection, "GET", "/api/stores/{storeId}/log/{from}/{to}/actions/{actionType}?offset={offset}", Object.class, false,
                    storeId, format(from), format(to), actionType, offset);
            List<?> items = asList(body);
            if (items.isEmpty()) {
                break;
            }
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> entry)) continue;
                Map<?, ?> actor = first(entry, "actor") instanceof Map<?, ?> a ? a : Map.of();
                Object dateReference = first(entry, "dateReference");
                Object performedAt = first(entry, "performedAt");
                if (dateReference == null || performedAt == null) continue;
                result.add(new FoodsharingPickupModels.StoreLogEntry(
                        instant(performedAt),
                        intValue(first(entry, "actionType")),
                        string(first(actor, "id")),
                        string(first(actor, "name")),
                        instant(dateReference)));
            }
            offset += items.size();
        }
        return result;
    }

    /** Past pickups with the foodsavers that were signed in. Pickups nobody was signed in for are not returned by Foodsharing. */
    public List<FoodsharingPickupModels.Pickup> pickupHistory(FoodsharingAdminConnection connection, long storeId, Instant from, Instant to) {
        Object body = get(connection, "GET", "/api/stores/{storeId}/pickups/history/{from}/{to}", Object.class, false, storeId, format(from), format(to));
        return parsePickupHistory(storeId, body);
    }

    /**
     * Parses {@code GET /api/stores/{storeId}/pickups/history/{from}/{to}}. Foodsharing returns one entry per occupied
     * slot ({@code date}, {@code confirmed}, {@code profile}), not one per pickup, so the entries are grouped by date.
     * The history has no slot count, so {@code totalSlots} is 0. Entries already grouped per pickup are accepted too.
     */
    static List<FoodsharingPickupModels.Pickup> parsePickupHistory(long storeId, Object body) {
        Map<Instant, List<FoodsharingPickupModels.PickupUser>> usersByDate = new LinkedHashMap<>();
        Map<Instant, Integer> totalSlotsByDate = new LinkedHashMap<>();
        for (Object item : asList(body)) {
            if (!(item instanceof Map<?, ?> entry)) continue;
            Object date = first(entry, "date", "pickupDate");
            if (date == null) continue;
            Instant pickupDate = instant(date);
            List<FoodsharingPickupModels.PickupUser> users = usersByDate.computeIfAbsent(pickupDate, d -> new ArrayList<>());
            Object totalSlots = first(entry, "totalSlots");
            if (totalSlots != null) totalSlotsByDate.merge(pickupDate, intValue(totalSlots), Math::max);
            List<?> slots = entry.get("profile") instanceof Map<?, ?> ? List.of(entry) : asList(first(entry, "occupiedSlots", "users", "slots", "participants"));
            for (Object userSlot : slots) {
                if (!(userSlot instanceof Map<?, ?> slot)) continue;
                Map<?, ?> profile = first(slot, "profile") instanceof Map<?, ?> p ? p : slot;
                String id = string(first(profile, "id", "userId"));
                if (id.isBlank() || users.stream().anyMatch(user -> user.id().equals(id))) continue;
                users.add(new FoodsharingPickupModels.PickupUser(id, string(first(profile, "name")), isTrue(first(slot, "isConfirmed", "confirmed")), instantOrNull(first(slot, "signUpDate"))));
            }
        }
        List<FoodsharingPickupModels.Pickup> result = new ArrayList<>();
        usersByDate.forEach((date, users) -> result.add(new FoodsharingPickupModels.Pickup(storeId, date, totalSlotsByDate.getOrDefault(date, 0), users)));
        return result;
    }

    /** Foodsharing sends flags as booleans or as 0/1. */
    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() != 0) || "1".equals(string(value)) || "true".equalsIgnoreCase(string(value));
    }

    @SuppressWarnings("unchecked")
    public List<FoodsharingPickupModels.StoreMember> members(FoodsharingAdminConnection connection, long storeId) {
        Object body = get(connection, "GET", "/api/stores/{storeId}/members", Object.class, false, storeId);
        List<FoodsharingPickupModels.StoreMember> result = new ArrayList<>();
        for (Object item : asList(body)) {
            Map<?, ?> member = (Map<?, ?>) item;
            result.add(new FoodsharingPickupModels.StoreMember(
                    longValue(first(member, "id", "userId")),
                    string(first(member, "name", "firstName")),
                    instantOrNull(first(member, "lastFetch", "lastFetchedAt", "lastPickupAt")),
                    intValue(first(member, "fetchCount"))));
        }
        return result;
    }

    public void confirm(FoodsharingAdminConnection connection, long storeId, Instant pickupDate, String userId) {
        if (!outboundGuard.allowFoodsharingUser(userId, "confirm pickup " + format(pickupDate) + " in store " + storeId)) return;
        runWithSessionRetry(connection, false, () -> {
            log.debug("Foodsharing request: PATCH /api/stores/{}/pickups/{}/users/{} user={} cookie={} csrf={}",
                    storeId, format(pickupDate), userId, connection.getFoodsharingEmail(),
                    fingerprint(cookie(connection)),
                    fingerprint(csrfToken(connection)));
            restClient.patch().uri("/api/stores/{storeId}/pickups/{pickupDate}/users/{userId}", storeId, format(pickupDate), userId)
                    .headers(h -> apply(h, connection)).retrieve().toBodilessEntity();
            return null;
        });
    }

    public List<RequestUser> requests(FoodsharingAdminConnection connection, long storeId) {
        Object body = get(connection, "GET", "/api/stores/{storeId}/requests", Object.class, false, storeId);
        List<RequestUser> result = new ArrayList<>();
        for (Object item : asList(body)) {
            Map<?, ?> request = (Map<?, ?>) item;
            Map<?, ?> profile = first(request, "profile", "user") instanceof Map<?, ?> p ? p : request;
            result.add(new RequestUser(string(first(profile, "id", "userId")), string(first(profile, "name")), doubleOrNull(first(request, "distanceInKm"))));
        }
        return result;
    }

    public void approveRequest(FoodsharingAdminConnection connection, long storeId, String userId) {
        if (!outboundGuard.allowFoodsharingUser(userId, "approve team request for store " + storeId)) return;
        runWithSessionRetry(connection, false, () -> {
            log.debug("Foodsharing request: PATCH /api/stores/{}/requests/{} user={} cookie={} csrf={}",
                    storeId, userId, connection.getFoodsharingEmail(), fingerprint(cookie(connection)), fingerprint(csrfToken(connection)));
            restClient.patch().uri("/api/stores/{storeId}/requests/{userId}", storeId, userId)
                    .headers(h -> apply(h, connection)).retrieve().toBodilessEntity();
            return null;
        });
    }

    public void declineRequest(FoodsharingAdminConnection connection, long storeId, String userId, String message) {
        if (!outboundGuard.allowFoodsharingUser(userId, "decline team request for store " + storeId + ": " + message)) return;
        runWithSessionRetry(connection, false, () -> {
            log.debug("Foodsharing request: DELETE /api/stores/{}/requests/{} user={} cookie={} csrf={}",
                    storeId, userId, connection.getFoodsharingEmail(), fingerprint(cookie(connection)), fingerprint(csrfToken(connection)));
            restClient.method(org.springframework.http.HttpMethod.DELETE).uri("/api/stores/{storeId}/requests/{userId}", storeId, userId)
                    .contentType(MediaType.APPLICATION_JSON).headers(h -> apply(h, connection))
                    .body(Map.of("message", message)).retrieve().toBodilessEntity();
            return null;
        });
    }

    public void sendStoreChatMessage(FoodsharingAdminConnection connection, long storeId, String body) {
        if (!outboundGuard.allowStoreChat(storeId, body)) return;
        runWithSessionRetry(connection, false, () -> {
            log.debug("Foodsharing request: GET /api/stores/{}/permissions user={} cookie={} csrf={}",
                    storeId, connection.getFoodsharingEmail(), fingerprint(cookie(connection)), fingerprint(csrfToken(connection)));
            Map<?, ?> permissions = restClient.get().uri("/api/stores/{storeId}/permissions", storeId)
                    .headers(h -> apply(h, connection)).retrieve().body(Map.class);
            Object teamConversationId = permissions == null ? null : first(permissions, "teamConversationId");
            if (teamConversationId == null || longValue(teamConversationId) <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                        List.of("Foodsharing store " + storeId + " has no team conversation."));
            }
            log.debug("Foodsharing request: POST /api/conversations/{}/messages user={} cookie={} csrf={}",
                    teamConversationId, connection.getFoodsharingEmail(), fingerprint(cookie(connection)), fingerprint(csrfToken(connection)));
            restClient.post().uri("/api/conversations/{conversationId}/messages", teamConversationId)
                    .contentType(MediaType.APPLICATION_JSON).headers(h -> apply(h, connection))
                    .body(Map.of("body", body)).retrieve().toBodilessEntity();
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    public void sendUserMessage(FoodsharingAdminConnection connection, String userId, String body) {
        if (DemoAccounts.isDemoId(userId)) return;
        if (!outboundGuard.allowFoodsharingUser(userId, body)) return;
        long numericUserId;
        try {
            numericUserId = Long.parseLong(userId);
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of("Foodsharing user ID is invalid: " + userId));
        }
        runWithSessionRetry(connection, false, () -> {
            Map<?, ?> conversation = restClient.post().uri("/api/conversations/lookup")
                    .contentType(MediaType.APPLICATION_JSON).headers(headers -> apply(headers, connection))
                    .body(Map.of("ids", List.of(numericUserId))).retrieve().body(Map.class);
            long conversationId = conversation == null ? 0 : longValue(first(conversation, "id"));
            if (conversationId <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.UNEXPECTED_ERROR,
                        List.of("Foodsharing did not return a conversation for user " + userId + "."));
            }
            restClient.post().uri("/api/conversations/{conversationId}/messages", conversationId)
                    .contentType(MediaType.APPLICATION_JSON).headers(headers -> apply(headers, connection))
                    .body(Map.of("body", body)).retrieve().toBodilessEntity();
            return null;
        });
    }

    public void decline(FoodsharingAdminConnection connection, long storeId, Instant pickupDate, String userId, String message) {
        if (!outboundGuard.allowFoodsharingUser(userId, "decline pickup " + format(pickupDate) + " in store " + storeId + ": " + message)) return;
        runWithSessionRetry(connection, false, () -> {
            log.debug("Foodsharing request: DELETE /api/stores/{}/pickups/{}/users/{} user={} cookie={} csrf={}",
                    storeId, format(pickupDate), userId, connection.getFoodsharingEmail(),
                    fingerprint(cookie(connection)),
                    fingerprint(csrfToken(connection)));
            restClient.method(org.springframework.http.HttpMethod.DELETE).uri("/api/stores/{storeId}/pickups/{pickupDate}/users/{userId}", storeId, format(pickupDate), userId)
                    .contentType(MediaType.APPLICATION_JSON).headers(h -> apply(h, connection))
                    .body(Map.of("message", message, "sendKickMessage", true)).retrieve().toBodilessEntity();
            return null;
        });
    }

    private <T> T get(FoodsharingAdminConnection c, String method, String uri, Class<T> type, boolean retried, Object... vars) {
        for (int attempt = 1; attempt <= MAX_GET_ATTEMPTS; attempt++) {
            try {
                return runWithSessionRetry(c, retried, () -> {
                    log.debug("Foodsharing request: {} {} user={} cookie={} csrf={}",
                            method,
                            String.format(uri.replace("{storeId}", "%s").replace("{pickupDate}", "%s").replace("{userId}", "%s").replace("{regionId}", "%s"), vars),
                            c.getFoodsharingEmail(),
                            fingerprint(cookie(c)),
                            fingerprint(csrfToken(c)));
                    return restClient.get().uri(uri, vars).headers(h -> apply(h, c)).retrieve().body(type);
                });
            } catch (ResourceAccessException ex) {
                if (attempt == MAX_GET_ATTEMPTS) {
                    throw ex;
                }
                log.warn("Foodsharing GET failed (attempt {}/{}); retrying after {} ms: {}",
                        attempt, MAX_GET_ATTEMPTS, GET_RETRY_DELAY_MILLIS, ex.getMessage());
                try {
                    Thread.sleep(GET_RETRY_DELAY_MILLIS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw new IllegalStateException("Foodsharing GET attempts exhausted");
    }

    private <T> T runWithSessionRetry(FoodsharingAdminConnection connection, boolean retried, RequestAction<T> action) {
        try {
            return action.run();
        } catch (HttpClientErrorException ex) {
            if (shouldReauthenticate(ex, retried)) {
                reauthenticate(connection);
                return runWithSessionRetry(connection, true, action);
            }
            throw ex;
        }
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

    private void reauthenticate(FoodsharingAdminConnection connection) {
        String password = cryptoService.decrypt(connection.getFoodsharingPasswordCiphertext());
        if (password == null || password.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Foodsharing connection expired and cannot be refreshed. Reconnect Foodsharing."));
        }
        log.debug("Foodsharing automation session expired, re-authenticating admin={} foodsharingUserId={} email={}",
                connection.getAdminUser().getId(), connection.getFoodsharingUserId(), connection.getFoodsharingEmail());
        Session session;
        try {
            session = login(connection.getFoodsharingEmail(), password);
        } catch (ApiException exception) {
            if (exception.getCode() != ApiErrorCode.INVALID_FOODSHARING_CREDENTIALS) {
                throw exception;
            }
            // The saved password no longer works: not a wrong entry of the current user, so say so instead.
            log.error("Foodsharing refused the login of the saved automation account {} (admin={}). Reconnect Foodsharing.",
                    connection.getFoodsharingEmail(), connection.getAdminUser().getId());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.FOODSHARING_ACCOUNT_LOGIN_FAILED);
        }
        Instant authenticatedAt = Instant.now();
        refreshedSessions.put(connection.getId(), new RefreshedSession(session, authenticatedAt));
        try {
            sessionTransaction.executeWithoutResult(status -> connectionRepository.updateSession(connection.getId(),
                    cryptoService.encrypt(session.cookie()), cryptoService.encrypt(session.csrf()), session.foodsharingUserId(), authenticatedAt));
        } catch (RuntimeException ex) {
            // The session is kept in memory for this instance; the next re-login tries to store it again.
            log.warn("Foodsharing session for admin connection {} could not be stored.", connection.getId(), ex);
        }
    }

    /** The re-login session, if it is newer than the one stored on {@code c} (a reconnect stores a newer one). */
    private Session refreshedSession(FoodsharingAdminConnection c) {
        RefreshedSession refreshed = c.getId() == null ? null : refreshedSessions.get(c.getId());
        if (refreshed == null || (c.getAuthenticatedAt() != null && !refreshed.authenticatedAt().isAfter(c.getAuthenticatedAt()))) {
            return null;
        }
        return refreshed.session();
    }

    private record RefreshedSession(Session session, Instant authenticatedAt) {}
    private void apply(HttpHeaders h, FoodsharingAdminConnection c) {
        String cookie = cookie(c);
        h.set(HttpHeaders.COOKIE, cookie);
        String csrf = csrfToken(c);
        if (csrf != null && !csrf.isBlank()) {
            h.set("X-CSRF-Token", csrf);
        }
    }
    private String cookie(FoodsharingAdminConnection c) {
        Session refreshed = refreshedSession(c);
        return refreshed != null ? refreshed.cookie() : cryptoService.decrypt(c.getSessionCookieCiphertext());
    }
    private String csrfToken(FoodsharingAdminConnection c) {
        Session refreshed = refreshedSession(c);
        String csrf = refreshed != null ? refreshed.csrf() : cryptoService.decrypt(c.getCsrfTokenCiphertext());
        if (csrf != null && !csrf.isBlank()) {
            return csrf;
        }
        return cookieValue(cookie(c), "FS_CSRF_TOKEN");
    }

    @FunctionalInterface
    private interface RequestAction<T> {
        T run();
    }
    private static final java.time.format.DateTimeFormatter API_DATE_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    private String format(Instant i) { return API_DATE_FORMAT.format(i); }
    private static List<?> asList(Object o) { return o instanceof List<?> l ? l : List.of(); }
    private static String extractCookieHeader(List<String> setCookies) {
        List<String> cookiePairs = new ArrayList<>();
        for (String setCookie : setCookies) {
            cookiePairs.add(setCookie.split(";", 2)[0]);
        }
        return String.join("; ", cookiePairs);
    }
    private static String extractCsrfToken(List<String> setCookies, String headerToken, Map<?, ?> body) {
        if (headerToken != null && !headerToken.isBlank()) {
            return headerToken;
        }
        for (String setCookie : setCookies) {
            String pair = setCookie.split(";", 2)[0];
            if (pair.startsWith("FS_CSRF_TOKEN=")) {
                return pair.substring("FS_CSRF_TOKEN=".length());
            }
        }
        if (body != null) {
            Object csrfToken = first(body, "csrfToken", "csrf", "token");
            if (csrfToken != null && !string(csrfToken).isBlank()) {
                return string(csrfToken);
            }
        }
        return null;
    }
    static String extractUserId(Map<?, ?> body) {
        if (body == null) {
            return null;
        }
        Object userId = first(body, "id", "userId", "foodsharingUserId");
        if (userId != null && !string(userId).isBlank()) {
            return string(userId);
        }
        Object user = first(body, "user");
        if (user instanceof Map<?, ?> nested) {
            Object nestedId = first(nested, "id", "userId");
            if (nestedId != null && !string(nestedId).isBlank()) {
                return string(nestedId);
            }
        }
        return null;
    }
    private static Object first(Map<?, ?> m, String... names) { for (String n:names) if (m.containsKey(n)) return m.get(n); return null; }
    private static String string(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long longValue(Object o) { return o instanceof Number n ? n.longValue() : Long.parseLong(string(o).isBlank()?"0":string(o)); }
    private static final java.time.ZoneId FOODSHARING_ZONE = java.time.ZoneId.of("Europe/Zurich");

    /** Foodsharing sends ISO instants, offset timestamps, or zone-less "yyyy-MM-dd HH:mm:ss" local times. */
    static Instant instant(Object o) {
        String text = string(o).trim();
        try {
            return java.time.OffsetDateTime.parse(text).toInstant();
        } catch (java.time.format.DateTimeParseException ignored) {
            return java.time.LocalDateTime.parse(text.replace(' ', 'T')).atZone(FOODSHARING_ZONE).toInstant();
        }
    }
    private static Instant instantOrNull(Object o) { return o == null || string(o).isBlank() ? null : instant(o); }
    private static int intValue(Object o) { return o instanceof Number n ? n.intValue() : Integer.parseInt(string(o).isBlank()?"0":string(o)); }
    private static Double doubleValue(Object o) { return o instanceof Number n ? n.doubleValue() : Double.parseDouble(string(o).isBlank()?"0":string(o)); }
    private static Double doubleOrNull(Object o) { return o == null || string(o).isBlank() ? null : doubleValue(o); }
    public record RequestUser(String id, String name, Double distanceInKm) {}
    private static String cookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }
        for (String cookie : cookieHeader.split(";\\s*")) {
            int index = cookie.indexOf('=');
            if (index > 0 && name.equals(cookie.substring(0, index))) {
                return cookie.substring(index + 1);
            }
        }
        return null;
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
    public record Session(String cookie, String csrf, String foodsharingUserId) {}
}
