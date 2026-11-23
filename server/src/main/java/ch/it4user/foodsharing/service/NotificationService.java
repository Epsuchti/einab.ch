package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.EinAb;
import ch.it4user.foodsharing.domain.entity.NotificationSubscription;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import ch.it4user.foodsharing.domain.enumtype.NotificationChannel;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.repository.EinAbRepository;
import ch.it4user.foodsharing.repository.NotificationSubscriptionRepository;
import ch.it4user.foodsharing.repository.SlotRepository;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Notifications about new EinAbs for signed-in users who are not verified yet. They are delivered as Foodsharing
 * messages and only contain EinAbs the user could actually book.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Set<SlotStatus> ACTIVE_BOOKING_STATUSES =
            Set.of(SlotStatus.BOOKED, SlotStatus.DONE);

    private final NotificationSubscriptionRepository notificationSubscriptionRepository;
    private final EinAbRepository einAbRepository;
    private final SlotRepository slotRepository;
    private final TokenService tokenService;
    private final FoodsharingMessageService messageService;
    private final EmailService emailService;
    private final NotificationTemplateService templateService;
    private final CurrentActorService currentActorService;
    private final AppProperties appProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final BezirkService bezirkService;
    private final BookingUserService bookingUserService;

    public NotificationService(NotificationSubscriptionRepository notificationSubscriptionRepository,
                               EinAbRepository einAbRepository,
                               SlotRepository slotRepository,
                               TokenService tokenService,
                               FoodsharingMessageService messageService,
                               EmailService emailService,
                               NotificationTemplateService templateService,
                               CurrentActorService currentActorService,
                               AppProperties appProperties,
                               ApplicationEventPublisher eventPublisher,
                               BezirkService bezirkService,
                               BookingUserService bookingUserService) {
        this.notificationSubscriptionRepository = notificationSubscriptionRepository;
        this.einAbRepository = einAbRepository;
        this.slotRepository = slotRepository;
        this.tokenService = tokenService;
        this.messageService = messageService;
        this.emailService = emailService;
        this.templateService = templateService;
        this.currentActorService = currentActorService;
        this.appProperties = appProperties;
        this.eventPublisher = eventPublisher;
        this.bezirkService = bezirkService;
        this.bookingUserService = bookingUserService;
    }

    /** The subscription of the signed-in user, if there ever was one. */
    @Transactional(readOnly = true)
    public Optional<NotificationSubscription> findMine() {
        return notificationSubscriptionRepository.findByUser(requireCurrentUser());
    }

    @Transactional
    public NotificationSubscription subscribe(String bezirkSlug, LanguageCode language, NotificationChannel channel, String email) {
        User user = requireCurrentUser();
        if (user.isVerified()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.NOTIFICATION_NOT_NEEDED_VERIFIED);
        }
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        bookingUserService.assignToBezirk(user, bezirk);
        LanguageCode normalizedLanguage = language == null ? LanguageCode.DE : language;
        NotificationChannel normalizedChannel = channel == null ? NotificationChannel.FOODSHARING : channel;
        String deliveryEmail = normalizedChannel == NotificationChannel.EMAIL ? normalizeEmail(email) : null;
        NotificationSubscription subscription = notificationSubscriptionRepository.findByUser(user)
                .orElseGet(() -> {
                    NotificationSubscription created = new NotificationSubscription();
                    created.setUser(user);
                    return created;
                });
        boolean wasActive = subscription.isActive() && subscription.getId() != null
                && normalizedChannel == subscription.getChannel()
                && java.util.Objects.equals(deliveryEmail, subscription.getDeliveryEmail());
        subscription.setBezirk(bezirk);
        subscription.setLanguage(normalizedLanguage);
        subscription.setChannel(normalizedChannel);
        subscription.setDeliveryEmail(deliveryEmail);
        subscription.setActive(true);
        if (subscription.getUnsubscribeToken() == null || subscription.getUnsubscribeToken().isBlank() || !wasActive) {
            subscription.setUnsubscribeToken(tokenService.generateToken());
        }
        NotificationSubscription saved = notificationSubscriptionRepository.save(subscription);
        if (!wasActive) {
            eventPublisher.publishEvent(new NotificationSubscriptionCreatedEvent(saved.getId()));
        }
        return saved;
    }

    @Transactional
    public Optional<NotificationSubscription> unsubscribeMine() {
        Optional<NotificationSubscription> subscription = notificationSubscriptionRepository.findByUser(requireCurrentUser());
        subscription.ifPresent(value -> value.setActive(false));
        return subscription;
    }

    @Transactional
    public NotificationSubscription unsubscribe(String token) {
        NotificationSubscription subscription = notificationSubscriptionRepository.findByUnsubscribeToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_UNSUBSCRIBE_TOKEN));
        subscription.setActive(false);
        return subscription;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleEinAbCreated(EinAbCreatedEvent event) {
        EinAb einAb = einAbRepository.findWithTeacherById(event.einAbId()).orElse(null);
        if (einAb == null) {
            return;
        }
        for (NotificationSubscription recipient : notificationSubscriptionRepository
                .findAllByBezirkAndActiveTrueAndUserNotNullOrderByCreatedAtAsc(einAb.getBezirk())) {
            User user = recipient.getUser();
            try {
                if (!isRelevant(user, einAb)) {
                    continue;
                }
                LanguageCode language = recipient.getLanguage() == null ? LanguageCode.DE : recipient.getLanguage();
                deliver(recipient, templateService.newEinAbSubject(language),
                        templateService.newEinAbBody(language, user.getName(), einAb, bookingUrl(recipient), unsubscribeUrl(recipient)));
            } catch (RuntimeException exception) {
                log.warn("Could not send EinAb notification to user {}: {}", user.getFoodsharingId(), exception.getMessage());
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleNotificationSubscriptionCreated(NotificationSubscriptionCreatedEvent event) {
        NotificationSubscription subscription = notificationSubscriptionRepository.findById(event.subscriptionId()).orElse(null);
        if (subscription == null || !subscription.isActive() || subscription.getUser() == null) {
            return;
        }
        LanguageCode language = subscription.getLanguage() == null ? LanguageCode.DE : subscription.getLanguage();
        try {
            deliver(subscription, templateService.subscriptionConfirmationSubject(language),
                    templateService.subscriptionConfirmationBody(language, subscription.getUser().getName(), unsubscribeUrl(subscription)));
        } catch (RuntimeException exception) {
            log.warn("Could not send notification confirmation to user {}: {}",
                    subscription.getUser().getFoodsharingId(), exception.getMessage());
        }
    }

    private void deliver(NotificationSubscription subscription, String subject, String body) {
        if (subscription.getChannel() == NotificationChannel.EMAIL && subscription.getDeliveryEmail() != null) {
            emailService.send(subscription.getUser().getFoodsharingId(), subscription.getDeliveryEmail(), subject, body, templateService.toHtml(body));
        } else {
            messageService.send(subscription.getUser().getFoodsharingId(), subject, body);
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, java.util.List.of("Email is required."));
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        try {
            new InternetAddress(normalizedEmail).validate();
        } catch (AddressException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, java.util.List.of("Email is invalid."));
        }
        return normalizedEmail;
    }

    /** Mirrors the booking rules of {@link PublicService#bookSlot}: only EinAbs the user could book are relevant. */
    boolean isRelevant(User user, EinAb einAb) {
        if (!user.isActive() || user.isVerified() || user.isCanGiveEinAbs()) {
            return false;
        }
        Bezirk bezirk = einAb.getBezirk();
        if (user.getBezirk() != null && user.getBezirk().isActive() && !user.getBezirk().getId().equals(bezirk.getId())) {
            return false;
        }
        if (user.getId().equals(einAb.getTeacher().getId())) {
            return false;
        }
        if (bezirk.getIgnoredCategories().contains(einAb.getCategory())) {
            return true;
        }
        long activeBookings = slotRepository.countByBookingUserAndStatusInExcludingIgnoredCategories(
                user, ACTIVE_BOOKING_STATUSES);
        if (activeBookings >= bezirk.getMaxActiveBookingsPerUser()) {
            return false;
        }
        if (bezirk.isPreventDuplicateTeacherBookings()
                && slotRepository.existsByBookingUserAndStatusInAndTeacherAndEinAbBezirkExcludingIgnoredCategories(
                user, ACTIVE_BOOKING_STATUSES, einAb.getTeacher(), bezirk)) {
            return false;
        }
        if (bezirk.isPreventDuplicateCategoryBookings()
                && slotRepository.existsByBookingUserAndStatusInAndEinAbCategoryAndEinAbBezirk(
                user, ACTIVE_BOOKING_STATUSES, einAb.getCategory(), bezirk)) {
            return false;
        }
        if (bezirk.isRequireFairteilerVisitByThirdEinAb() && !einAb.isVisitFairteiler()
                && activeBookings >= bezirk.getMaxActiveBookingsPerUser() - 1
                && !slotRepository.existsByBookingUserAndStatusInAndEinAbVisitFairteilerTrue(
                user, ACTIVE_BOOKING_STATUSES)) {
            return false;
        }
        Integer minimumPickupCount = einAb.getMinimumPickupCount();
        return minimumPickupCount == null
                || slotRepository.countByBookingUserAndStatusAndEinAbBezirkExcludingIgnoredCategories(
                user, SlotStatus.DONE, bezirk) >= minimumPickupCount;
    }

    private User requireCurrentUser() {
        return currentActorService.findCurrentUser()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTHENTICATION_REQUIRED));
    }

    private String bookingUrl(NotificationSubscription subscription) {
        return appProperties.getFrontend().getBaseUrl() + "/bezirke/" + subscription.getBezirk().getSlug();
    }

    private String unsubscribeUrl(NotificationSubscription subscription) {
        return bookingUrl(subscription) + "/unsubscribe?token=" + subscription.getUnsubscribeToken();
    }
}
