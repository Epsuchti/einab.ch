package ch.it4user.foodsharing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends push notifications through the Firebase Cloud Messaging HTTP v1 API, for Android and (via Firebase's APNs
 * connection) iOS. Authenticates with a Firebase service account; the OAuth token is signed here so no Google client
 * library is needed. Without configured credentials {@link #isConfigured()} is false and nothing is sent.
 */
@Component
public class FirebaseMessagingClient {

    private static final Logger log = LoggerFactory.getLogger(FirebaseMessagingClient.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.create();
    private final ServiceAccount account;
    private final String fcmBaseUrl;
    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public FirebaseMessagingClient(AppProperties appProperties) {
        this.account = loadServiceAccount(appProperties.getPush());
        this.fcmBaseUrl = appProperties.getPush().getFcmBaseUrl().replaceAll("/+$", "");
        if (account != null) {
            log.info("Push notifications enabled for Firebase project {}", account.projectId());
        }
    }

    public boolean isConfigured() {
        return account != null;
    }

    /** Result of one send: delivered, or the token is no longer valid and should be forgotten, or another failure. */
    public enum Result { SENT, INVALID_TOKEN, FAILED }

    /**
     * @param data extra key/value pairs for the app; "url" is the page to open when the notification is tapped
     */
    public Result send(String token, String title, String body, Map<String, String> data) {
        if (account == null) {
            return Result.FAILED;
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", token);
        message.put("notification", Map.of("title", title, "body", body));
        message.put("data", data);
        message.put("android", Map.of(
                "priority", "high",
                "notification", Map.of("channel_id", "einab", "default_sound", true)));
        message.put("apns", Map.of("payload", Map.of("aps", Map.of("sound", "default"))));
        try {
            restClient.post()
                    .uri(fcmBaseUrl + "/v1/projects/{project}/messages:send", account.projectId())
                    .header("Authorization", "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(Map.of("message", message)))
                    .retrieve()
                    .toBodilessEntity();
            return Result.SENT;
        } catch (RestClientResponseException exception) {
            if (isInvalidToken(exception.getStatusCode(), exception.getResponseBodyAsString())) {
                return Result.INVALID_TOKEN;
            }
            log.warn("Push notification failed with HTTP {}: {}", exception.getStatusCode().value(),
                    truncate(exception.getResponseBodyAsString()));
            return Result.FAILED;
        } catch (IOException | RuntimeException exception) {
            log.warn("Push notification failed: {}", exception.getMessage());
            return Result.FAILED;
        }
    }

    /** 404 UNREGISTERED: the app was uninstalled or the token rotated. 400 for a malformed token. */
    private static boolean isInvalidToken(HttpStatusCode status, String responseBody) {
        String body = responseBody == null ? "" : responseBody;
        return status.value() == 404
                || body.contains("UNREGISTERED")
                || (status.value() == 400 && body.contains("registration token"));
    }

    private synchronized String accessToken() throws IOException {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiresAt.minusSeconds(60))) {
            return accessToken;
        }
        Instant now = Instant.now();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = base64Url(objectMapper.writeValueAsBytes(Map.of(
                "iss", account.clientEmail(),
                "scope", SCOPE,
                "aud", account.tokenUri(),
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(3600).getEpochSecond())));
        String unsigned = header + "." + claims;
        String assertion;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(account.privateKey());
            signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
            assertion = unsigned + "." + base64Url(signature.sign());
        } catch (java.security.GeneralSecurityException exception) {
            throw new IOException("Could not sign the Firebase token request", exception);
        }
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        String response = restClient.post()
                .uri(account.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        JsonNode json = objectMapper.readTree(response);
        accessToken = json.path("access_token").asText();
        accessTokenExpiresAt = now.plusSeconds(json.path("expires_in").asLong(3600));
        return accessToken;
    }

    private ServiceAccount loadServiceAccount(AppProperties.Push push) {
        try {
            String json = push.getFirebaseCredentialsJson();
            if (json == null || json.isBlank()) {
                log.info("Push notifications disabled: no Firebase service account configured.");
                return null;
            }
            json = json.trim();
            // Also accepted base64-encoded (one line, no quoting issues in Kubernetes secrets), like mument does it.
            if (!json.startsWith("{")) {
                json = new String(java.util.Base64.getMimeDecoder().decode(json), java.nio.charset.StandardCharsets.UTF_8);
            }
            JsonNode node = objectMapper.readTree(json);
            String pem = node.path("private_key").asText()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            String tokenUri = node.path("token_uri").asText(DEFAULT_TOKEN_URI);
            return new ServiceAccount(node.path("project_id").asText(), node.path("client_email").asText(),
                    tokenUri.isBlank() ? DEFAULT_TOKEN_URI : tokenUri, privateKey);
        } catch (Exception exception) {
            log.error("Push notifications disabled: the Firebase service account could not be read: {}", exception.getMessage());
            return null;
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String truncate(String text) {
        if (text == null) return "";
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300) + "…";
    }

    private record ServiceAccount(String projectId, String clientEmail, String tokenUri, PrivateKey privateKey) {}
}
