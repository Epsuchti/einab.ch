package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.PushDevice;
import ch.it4user.foodsharing.domain.enumtype.PushPlatform;
import ch.it4user.foodsharing.repository.PushDeviceRepository;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Native push notifications for users of the store app. Every message the platform sends to a user (Foodsharing
 * message or email) is additionally pushed to the phones on which that user turned on notifications in the app.
 * Sending happens in the background, so a slow or failing push never delays or breaks the message itself.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_BODY_LENGTH = 900;
    private static final int MAX_TOKEN_LENGTH = 512;

    private final PushDeviceRepository pushDeviceRepository;
    private final FirebaseMessagingClient firebaseClient;
    private final OutboundMessageGuard outboundGuard;
    private final AppProperties appProperties;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "push-notifications");
        thread.setDaemon(true);
        return thread;
    });

    public PushNotificationService(PushDeviceRepository pushDeviceRepository,
                                   FirebaseMessagingClient firebaseClient,
                                   OutboundMessageGuard outboundGuard,
                                   AppProperties appProperties,
                                   PlatformTransactionManager transactionManager) {
        this.pushDeviceRepository = pushDeviceRepository;
        this.firebaseClient = firebaseClient;
        this.outboundGuard = outboundGuard;
        this.appProperties = appProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Called by the app on every start while signed in; moves the token to this user if another user had it. */
    @Transactional
    public void registerDevice(String foodsharingId, String token, PushPlatform platform) {
        String normalizedToken = normalizeToken(token);
        PushDevice device = pushDeviceRepository.findByToken(normalizedToken).orElseGet(PushDevice::new);
        device.setFoodsharingId(foodsharingId.trim());
        device.setToken(normalizedToken);
        device.setPlatform(platform == null ? PushPlatform.ANDROID : platform);
        device.setLastSeenAt(Instant.now());
        pushDeviceRepository.save(device);
    }

    /** Push turned off in the app, or signed out on this phone. */
    @Transactional
    public void unregisterDevice(String foodsharingId, String token) {
        String normalizedToken = normalizeToken(token);
        pushDeviceRepository.findByToken(normalizedToken)
                .filter(device -> device.getFoodsharingId().equalsIgnoreCase(foodsharingId.trim()))
                .ifPresent(pushDeviceRepository::delete);
    }

    /** Pushes a copy of a message the user is being sent. Returns immediately. */
    public void sendToUser(String foodsharingId, String subject, String body) {
        if (!firebaseClient.isConfigured() || foodsharingId == null || foodsharingId.isBlank()) {
            return;
        }
        try {
            executor.execute(() -> deliver(foodsharingId.trim(), subject, body));
        } catch (RejectedExecutionException exception) {
            log.warn("Push notification for {} skipped: {}", foodsharingId, exception.getMessage());
        }
    }

    private void deliver(String foodsharingId, String subject, String body) {
        try {
            List<PushDevice> devices = pushDeviceRepository.findAllByFoodsharingId(foodsharingId);
            // Demo accounts only have the devices of the testers signed in with them, so they get pushes on the test server.
            if (devices.isEmpty() || (!DemoAccounts.isDemoId(foodsharingId)
                    && !outboundGuard.allowFoodsharingUser(foodsharingId, "push: " + subject))) {
                return;
            }
            String title = truncate(subject == null || subject.isBlank() ? "EinAb" : subject.trim(), MAX_TITLE_LENGTH);
            String text = truncate(pushText(body), MAX_BODY_LENGTH);
            Map<String, String> data = Map.of("url", appPath(body));
            for (PushDevice device : devices) {
                FirebaseMessagingClient.Result result = firebaseClient.send(device.getToken(), title, text, data);
                if (result == FirebaseMessagingClient.Result.INVALID_TOKEN) {
                    transactionTemplate.executeWithoutResult(status -> pushDeviceRepository.deleteByToken(device.getToken()));
                    log.info("Forgot push device of {}: the app is no longer installed or the token changed.", foodsharingId);
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Push notification for {} failed: {}", foodsharingId, exception.getMessage());
        }
    }

    /** Devices the app has not reported for a long time (uninstalled without Firebase telling us). */
    @Scheduled(cron = "0 40 3 * * *", zone = "Europe/Zurich")
    @Transactional
    public void forgetStaleDevices() {
        int removed = pushDeviceRepository.deleteByLastSeenAtBefore(
                Instant.now().minus(appProperties.getPush().getDeviceRetentionDays(), ChronoUnit.DAYS));
        if (removed > 0) {
            log.info("Forgot {} push devices that were not seen for {} days.", removed, appProperties.getPush().getDeviceRetentionDays());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /**
     * The message text without links (a tap opens the linked page instead), without the greeting line and without
     * lines that only introduced a link ("Buchungen öffnen: https://…").
     */
    static String pushText(String body) {
        if (body == null) {
            return "";
        }
        List<String> lines = body.replace("\r\n", "\n").replace('\r', '\n').lines()
                .map(PushNotificationService::withoutLink)
                .filter(line -> line != null)
                .toList();
        int start = 0;
        while (start < lines.size() && lines.get(start).isEmpty()) start++;
        if (start < lines.size() && lines.get(start).endsWith(",") && lines.get(start).length() <= 60) {
            start++;
        }
        return String.join("\n", lines.subList(start, lines.size())).strip().replaceAll("\n{3,}", "\n\n");
    }

    /** The line without its links; null when the line was only a link with its label ("Buchungen öffnen: …"). */
    private static String withoutLink(String line) {
        if (!URL.matcher(line).find()) {
            return line.strip();
        }
        String rest = URL.matcher(line).replaceAll("").strip();
        return rest.isEmpty() || rest.endsWith(":") ? null : rest;
    }

    /** App page of the first einab.ch link in the message (not the unsubscribe or sign-in link), or "" for none. */
    String appPath(String body) {
        if (body == null) {
            return "";
        }
        String baseUrl = appProperties.getFrontend().getBaseUrl().replaceAll("/+$", "");
        Matcher matcher = URL.matcher(body);
        while (matcher.find()) {
            String url = matcher.group().replaceAll("[).,;!?>]+$", "");
            if (url.startsWith(baseUrl + "/") && !url.contains("/unsubscribe") && !url.contains("/verify-login")) {
                return url.substring(baseUrl.length());
            }
        }
        return "";
    }

    private static String normalizeToken(String token) {
        String normalized = token == null ? "" : token.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_TOKEN_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("Invalid push token."));
        }
        return normalized;
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1).strip() + "…";
    }
}
