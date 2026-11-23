package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.config.AppVersionResolver;
import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.SupportRequest;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import ch.it4user.foodsharing.domain.enumtype.SupportClientPlatform;
import ch.it4user.foodsharing.domain.enumtype.SupportRequestCategory;
import ch.it4user.foodsharing.domain.enumtype.SupportRequestStatus;
import ch.it4user.foodsharing.repository.BezirkRepository;
import ch.it4user.foodsharing.repository.SupportRequestRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Support requests from the help page. Each new request is sent as a Foodsharing message to the admins of the
 * sender's Bezirk (all admins when the Bezirk has none), and listed on the admins' support page.
 */
@Service
public class SupportRequestService {

    private static final Logger log = LoggerFactory.getLogger(SupportRequestService.class);
    private static final int MAX_REQUESTS_PER_IP = 5;
    private static final Duration RATE_WINDOW = Duration.ofHours(1);

    private final SupportRequestRepository supportRequestRepository;
    private final UserRepository userRepository;
    private final BezirkRepository bezirkRepository;
    private final CurrentActorService currentActorService;
    private final AppVersionResolver appVersionResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final FoodsharingMessageService foodsharingMessageService;
    private final MessageTemplateService messageTemplateService;
    private final AppProperties appProperties;
    private final Map<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();

    public SupportRequestService(SupportRequestRepository supportRequestRepository, UserRepository userRepository,
            BezirkRepository bezirkRepository, CurrentActorService currentActorService, AppVersionResolver appVersionResolver,
            ApplicationEventPublisher eventPublisher, FoodsharingMessageService foodsharingMessageService,
            MessageTemplateService messageTemplateService, AppProperties appProperties) {
        this.supportRequestRepository = supportRequestRepository;
        this.userRepository = userRepository;
        this.bezirkRepository = bezirkRepository;
        this.currentActorService = currentActorService;
        this.appVersionResolver = appVersionResolver;
        this.eventPublisher = eventPublisher;
        this.foodsharingMessageService = foodsharingMessageService;
        this.messageTemplateService = messageTemplateService;
        this.appProperties = appProperties;
    }

    public record NewSupportRequest(SupportRequestCategory category, String message, String bezirkSlug,
            String contactFoodsharingId, String contactName, String clientVersion, SupportClientPlatform platform,
            String pageUrl, String userAgent) {
    }

    @Transactional
    public SupportRequest create(NewSupportRequest input, String clientIp) {
        User user = currentActorService.findCurrentUser().orElse(null);
        String message = trimToNull(input.message(), 4000);
        String contactFoodsharingId = user != null ? user.getFoodsharingId() : trimToNull(input.contactFoodsharingId(), 64);
        if (message == null || input.category() == null || contactFoodsharingId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                    List.of(message == null ? "message" : input.category() == null ? "category" : "contactFoodsharingId"));
        }
        checkRate(clientIp);

        SupportRequest request = new SupportRequest();
        request.setUser(user);
        request.setContactFoodsharingId(contactFoodsharingId);
        request.setContactName(user != null ? user.getName() : trimToNull(input.contactName(), 255));
        String bezirkSlug = trimToNull(input.bezirkSlug(), 100);
        Bezirk userBezirk = user == null || user.getBezirk() == null || !user.getBezirk().isActive() ? null : user.getBezirk();
        request.setBezirk(bezirkSlug == null ? userBezirk : bezirkRepository.findBySlugAndActiveTrue(bezirkSlug).orElse(userBezirk));
        request.setCategory(input.category());
        request.setMessage(message);
        request.setStatus(SupportRequestStatus.OPEN);
        request.setClientVersion(trimToNull(input.clientVersion(), 50));
        request.setServerVersion(trimToNull(appVersionResolver.resolve(), 50));
        request.setPlatform(input.platform());
        request.setPageUrl(trimToNull(input.pageUrl(), 500));
        request.setUserAgent(trimToNull(input.userAgent(), 500));
        SupportRequest saved = supportRequestRepository.save(request);
        eventPublisher.publishEvent(new SupportRequestCreatedEvent(saved.getId()));
        return saved;
    }

    /** Tells the Bezirk admins on Foodsharing. A failed message never fails the request; it is still on the support page. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void notifyAdmins(SupportRequestCreatedEvent event) {
        SupportRequest request = supportRequestRepository.findById(event.supportRequestId()).orElse(null);
        if (request == null) {
            return;
        }
        Bezirk bezirk = request.getBezirk();
        List<User> admins = bezirk == null ? List.of() : userRepository.findAllByCanManageUsersTrueAndActiveTrueAndBezirk(bezirk);
        if (admins.isEmpty()) {
            admins = userRepository.findAllByCanManageUsersTrueAndActiveTrue();
        }
        String supportUrl = appProperties.getFrontend().getBaseUrl() + "/bezirke/"
                + (bezirk != null ? bezirk.getSlug() : admins.stream().map(User::getBezirk).filter(b -> b != null && b.isActive()).map(Bezirk::getSlug).findFirst().orElse(""))
                + "/admin?tab=support";
        for (User admin : admins) {
            if (admin.getFoodsharingId() == null || admin.getFoodsharingId().equalsIgnoreCase(request.getContactFoodsharingId())) {
                continue;
            }
            LanguageCode language = admin.getPreferredLanguage() == null ? LanguageCode.DE : admin.getPreferredLanguage();
            try {
                foodsharingMessageService.send(admin.getFoodsharingId(),
                        messageTemplateService.supportRequestSubject(language, request.getCategory()),
                        messageTemplateService.supportRequestBody(language, request, bezirk == null ? null : bezirk.getName(), supportUrl));
            } catch (RuntimeException exception) {
                log.warn("Could not tell admin {} about support request {}: {}", admin.getFoodsharingId(), request.getId(), exception.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public Page<SupportRequest> list(SupportRequestStatus status, int page, int size) {
        currentActorService.requireAdmin();
        return supportRequestRepository.findPage(status, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    @Transactional(readOnly = true)
    public long openCount() {
        return supportRequestRepository.countByStatus(SupportRequestStatus.OPEN);
    }

    @Transactional
    public SupportRequest updateStatus(UUID id, SupportRequestStatus status) {
        User admin = currentActorService.requireAdmin();
        SupportRequest request = supportRequestRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND));
        request.setStatus(status);
        if (status == SupportRequestStatus.DONE) {
            request.setDoneAt(Instant.now());
            request.setDoneByName(admin.getName());
        } else {
            request.setDoneAt(null);
            request.setDoneByName(null);
        }
        // Load the lazy relations inside the transaction; the controller maps them afterwards.
        if (request.getUser() != null) {
            request.getUser().getPhoneNumber();
        }
        if (request.getBezirk() != null) {
            request.getBezirk().getName();
        }
        return request;
    }

    private void checkRate(String clientIp) {
        if (clientIp == null) {
            return;
        }
        Instant now = Instant.now();
        Instant windowStart = now.minus(RATE_WINDOW);
        boolean[] allowed = {true};
        requestsByIp.compute(clientIp, (ip, requests) -> {
            Deque<Instant> queue = requests == null ? new ArrayDeque<>() : requests;
            while (!queue.isEmpty() && queue.peekFirst().isBefore(windowStart)) {
                queue.pollFirst();
            }
            if (queue.size() >= MAX_REQUESTS_PER_IP) {
                allowed[0] = false;
            } else {
                queue.addLast(now);
            }
            return queue;
        });
        requestsByIp.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (!allowed[0]) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.TOO_MANY_SUPPORT_REQUESTS);
        }
    }

    private static String trimToNull(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
