package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import ch.it4user.foodsharing.domain.enumtype.EinAbCategory;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.repository.SlotRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class PublicService {

    private static final Set<SlotStatus> ACTIVE_BOOKING_STATUSES = Set.of(SlotStatus.BOOKED, SlotStatus.DONE);
    private static final Set<SlotStatus> EINAB_UPDATE_NOTIFICATION_STATUSES = Set.of(SlotStatus.BOOKED);

    private final SlotRepository slotRepository;
    private final BezirkService bezirkService;
    private final BookingUserService bookingUserService;
    private final FoodsharingMessageService messageService;
    private final MessageTemplateService messageTemplateService;
    private final AppProperties appProperties;
    private final OnboardingService onboardingService;

    public PublicService(SlotRepository slotRepository,
                         BezirkService bezirkService,
                         BookingUserService bookingUserService,
                         FoodsharingMessageService messageService,
                         MessageTemplateService messageTemplateService,
                         AppProperties appProperties,
                         OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
        this.slotRepository = slotRepository;
        this.bezirkService = bezirkService;
        this.bookingUserService = bookingUserService;
        this.messageService = messageService;
        this.messageTemplateService = messageTemplateService;
        this.appProperties = appProperties;
    }

    public Page<Slot> findAvailableSlots(String bezirkSlug,
                                         String search,
                                         EinAbCategory category,
                                         Boolean visitFairteiler,
                                         int page,
                                         int size,
                                         User bookingUser) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        return slotRepository.findAvailableSlots(
                bezirk,
                normalizeSearch(search),
                category,
                visitFairteiler,
                bookingUser,
                PageRequest.of(Math.max(page, 0), normalizeSize(size)));
    }

    @Transactional
    public Slot bookSlot(String bezirkSlug, UUID slotId, String foodsharingId, LanguageCode language) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        User bookingUser = bookingUserService.getOrCreate(foodsharingId, language, bezirk);
        if (!bookingUser.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.BOOKING_USER_DISABLED);
        }
        bookingUserService.assignToBezirk(bookingUser, bezirk);
        // Booking is normal app use: only after the privacy consent and the Bezirk info step.
        onboardingService.requireCompleted(bookingUser);

        Slot slot = slotRepository.findForUpdateByIdAndBezirk(slotId, bezirk)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND));
        if (slot.getStatus() != SlotStatus.AVAILABLE) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.SLOT_NOT_AVAILABLE);
        }
        if (slotRepository.existsByBookingUserAndStatusInAndEinAb(
                bookingUser, ACTIVE_BOOKING_STATUSES, slot.getEinAb())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.USER_ALREADY_BOOKED_IN_EINAB);
        }
        boolean ignoredCategory = bezirk.getIgnoredCategories().contains(slot.getEinAb().getCategory());
        if (!ignoredCategory && bezirk.isPreventDuplicateTeacherBookings()
                && slotRepository.existsByBookingUserAndStatusInAndTeacherAndEinAbBezirkExcludingIgnoredCategories(
                bookingUser, ACTIVE_BOOKING_STATUSES, slot.getTeacher(), bezirk)) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.USER_ALREADY_BOOKED_WITH_TEACHER);
        }
        if (!ignoredCategory && bezirk.isPreventDuplicateCategoryBookings()
                && slotRepository.existsByBookingUserAndStatusInAndEinAbCategoryAndEinAbBezirk(
                bookingUser, ACTIVE_BOOKING_STATUSES, slot.getEinAb().getCategory(), bezirk)) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.USER_ALREADY_BOOKED_IN_CATEGORY);
        }
        // The limit counts past and upcoming EinAbs (BOOKED and DONE) in every Bezirk. Only bookings that were
        // cancelled or marked as not counted (no-show) are CANCELLED and do not count. The limit itself is the one
        // of the Bezirk being booked, which is always the user's own Bezirk.
        long countedEinAbs = ignoredCategory ? 0 : slotRepository.countByBookingUserAndStatusInExcludingIgnoredCategories(
                bookingUser, ACTIVE_BOOKING_STATUSES);
        if (!ignoredCategory && countedEinAbs >= bezirk.getMaxActiveBookingsPerUser()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.USER_BOOKING_LIMIT_REACHED);
        }
        if (!ignoredCategory && bezirk.isRequireFairteilerVisitByThirdEinAb()
                && !slot.getEinAb().isVisitFairteiler()
                && countedEinAbs >= bezirk.getMaxActiveBookingsPerUser() - 1
                && !slotRepository.existsByBookingUserAndStatusInAndEinAbVisitFairteilerTrue(
                bookingUser, ACTIVE_BOOKING_STATUSES)) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.FAIRTEILER_VISIT_REQUIRED_FOR_THIRD_EINAB);
        }
        Integer minimumPickupCount = slot.getEinAb().getMinimumPickupCount();
        if (!ignoredCategory && minimumPickupCount != null
                && slotRepository.countByBookingUserAndStatusAndEinAbBezirkExcludingIgnoredCategories(
                bookingUser, SlotStatus.DONE, bezirk) < minimumPickupCount) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.MINIMUM_PICKUP_COUNT_NOT_REACHED);
        }

        slot.setBookingUser(bookingUser);
        slot.setStatus(SlotStatus.BOOKED);
        slot.setBookedAt(java.time.Instant.now());
        if (slot.getEinAb().getCategory() == EinAbCategory.ONLINE) {
            Slot nextOnlineSlot = new Slot();
            nextOnlineSlot.setEinAb(slot.getEinAb());
            nextOnlineSlot.setTeacher(slot.getEinAb().getTeacher());
            nextOnlineSlot.setStatus(SlotStatus.AVAILABLE);
            slotRepository.save(nextOnlineSlot);
        }
        sendBookingConfirmationMessage(slot);
        sendTeacherBookingConfirmationMessage(slot);
        return slot;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeSearch(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : "%" + normalized.toLowerCase() + "%";
    }

    private void sendBookingConfirmationMessage(Slot slot) {
        String manageUrl = appProperties.getFrontend().getBaseUrl()
                + "/bezirke/" + slot.getEinAb().getBezirk().getSlug()
                + "/my-bookings";
        LanguageCode language = slot.getBookingUser().getPreferredLanguage();
        messageService.send(
                slot.getBookingUser().getFoodsharingId(),
                messageTemplateService.bookingConfirmationSubject(language),
                messageTemplateService.bookingConfirmationBody(language, slot, manageUrl));
    }

    private void sendTeacherBookingConfirmationMessage(Slot slot) {
        User teacher = slot.getTeacher();
        LanguageCode language = teacher.getPreferredLanguage();
        String teacherDashboardUrl = appProperties.getFrontend().getBaseUrl()
                + "/bezirke/" + slot.getEinAb().getBezirk().getSlug()
                + "/teacher";
        messageService.send(
                teacher.getFoodsharingId(),
                messageTemplateService.teacherBookingConfirmationSubject(language),
                messageTemplateService.teacherBookingConfirmationBody(language, slot, teacherDashboardUrl));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleEinAbUpdated(EinAbUpdatedEvent event) {
        Set<UUID> notifiedBookingUsers = new HashSet<>();
        for (Slot slot : slotRepository.findAllByEinAbIdAndStatusIn(event.einAbId(), EINAB_UPDATE_NOTIFICATION_STATUSES)) {
            User bookingUser = slot.getBookingUser();
            if (bookingUser == null || !notifiedBookingUsers.add(bookingUser.getId())) {
                continue;
            }
            LanguageCode language = bookingUser.getPreferredLanguage() == null
                    ? LanguageCode.DE
                    : bookingUser.getPreferredLanguage();
            messageService.send(
                    bookingUser.getFoodsharingId(),
                    messageTemplateService.einAbUpdatedSubject(language),
                    messageTemplateService.einAbUpdatedBody(language, slot));
        }
    }

    private int normalizeSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
