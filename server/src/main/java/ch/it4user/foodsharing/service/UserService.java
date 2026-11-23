package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.enumtype.LanguageCode;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.repository.SlotRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Pattern PHONE_NUMBER_PATTERN = Pattern.compile("^\\+?[0-9 ()/.-]+$");

    private final BookingUserService bookingUserService;
    private final BezirkService bezirkService;
    private final SlotRepository slotRepository;
    private final FoodsharingMessageService foodsharingMessageService;
    private final MessageTemplateService messageTemplateService;

    public UserService(BookingUserService bookingUserService,
                       BezirkService bezirkService,
                       SlotRepository slotRepository,
                       FoodsharingMessageService foodsharingMessageService,
                       MessageTemplateService messageTemplateService) {
        this.bookingUserService = bookingUserService;
        this.bezirkService = bezirkService;
        this.slotRepository = slotRepository;
        this.foodsharingMessageService = foodsharingMessageService;
        this.messageTemplateService = messageTemplateService;
    }

    public Page<Slot> getBookingsByFoodsharingId(String bezirkSlug, String foodsharingId, int page, int size) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        User bookingUser = bookingUserService.getByFoodsharingId(foodsharingId);
        ensureUserCanUseBezirk(bookingUser, bezirk);
        return slotRepository.findAllByBookingUserAndStatusesAndBezirk(
                bookingUser,
                Set.of(SlotStatus.BOOKED, SlotStatus.DONE),
                bezirk,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    public User getProfileByFoodsharingId(String bezirkSlug, String foodsharingId) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        User bookingUser = bookingUserService.getByFoodsharingId(foodsharingId);
        ensureUserCanUseBezirk(bookingUser, bezirk);
        return bookingUser;
    }

    public User getProfileByFoodsharingId(String foodsharingId) {
        return bookingUserService.getByFoodsharingId(foodsharingId);
    }

    @Transactional
    public User updatePhoneNumber(String foodsharingId, String phoneNumber) {
        User user = bookingUserService.getByFoodsharingId(foodsharingId);
        String normalizedPhoneNumber = phoneNumber == null ? null : phoneNumber.trim();
        if (normalizedPhoneNumber == null || normalizedPhoneNumber.isEmpty() || normalizedPhoneNumber.length() > 50
                || !PHONE_NUMBER_PATTERN.matcher(normalizedPhoneNumber).matches()
                || normalizedPhoneNumber.replaceAll("\\D", "").length() < 7
                || normalizedPhoneNumber.replaceAll("\\D", "").length() > 15) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, List.of("phoneNumber"));
        }
        user.setPhoneNumber(normalizedPhoneNumber);
        return user;
    }

    @Transactional
    public Slot cancelBooking(String bezirkSlug, String foodsharingId, UUID slotId) {
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        User bookingUser = bookingUserService.getByFoodsharingId(foodsharingId);
        ensureUserCanUseBezirk(bookingUser, bezirk);
        Slot slot = slotRepository.findForUpdateByIdAndBezirk(slotId, bezirk)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BOOKING_NOT_FOUND));
        if (slot.getBookingUser() == null || !foodsharingId.equalsIgnoreCase(slot.getBookingUser().getFoodsharingId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.ONLY_OWN_BOOKINGS_CANCELLABLE);
        }
        if (slot.getStatus() != SlotStatus.BOOKED) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ONLY_BOOKED_APPOINTMENTS_CANCELLABLE);
        }
        if (!slot.getEinAb().getStartDateTime().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.PAST_BOOKING_NOT_CANCELLABLE);
        }
        sendTeacherCancellationMessage(slot);
        slot.setStatus(SlotStatus.AVAILABLE);
        slot.setBookingUser(null);
        slot.setBookedAt(null);
        slot.setDoneAt(null);
        return slot;
    }

    private void sendTeacherCancellationMessage(Slot slot) {
        User teacher = slot.getTeacher();
        LanguageCode language = teacher.getPreferredLanguage() == null ? LanguageCode.DE : teacher.getPreferredLanguage();
        foodsharingMessageService.send(
                teacher.getFoodsharingId(),
                messageTemplateService.teacherBookingCancellationSubject(language),
                messageTemplateService.teacherBookingCancellationBody(language, slot));
    }

    private void ensureUserCanUseBezirk(User bookingUser, Bezirk bezirk) {
        if (bookingUser.getBezirk() != null && bookingUser.getBezirk().isActive()
                && !bookingUser.getBezirk().getId().equals(bezirk.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.USER_BEZIRK_MISMATCH);
        }
    }
}
