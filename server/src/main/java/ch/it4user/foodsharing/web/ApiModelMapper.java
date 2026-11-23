package ch.it4user.foodsharing.web;

import ch.it4user.foodsharing.domain.entity.BookingComment;
import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.EinAb;
import ch.it4user.foodsharing.domain.entity.Slot;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.service.FoodsharingBezirkMessageConnectionService;
import ch.it4user.foodsharing.domain.entity.NotificationSubscription;
import ch.it4user.foodsharing.domain.enumtype.SlotStatus;
import ch.it4user.foodsharing.openapi.model.AdminBookingUserPageResponse;
import ch.it4user.foodsharing.openapi.model.AdminBookingUserResponse;
import ch.it4user.foodsharing.openapi.model.AdminBezirkResponse;
import ch.it4user.foodsharing.openapi.model.BezirkInfoResponse;
import ch.it4user.foodsharing.openapi.model.AdminBezirkSummary;
import ch.it4user.foodsharing.openapi.model.AdminEinAbListResponse;
import ch.it4user.foodsharing.openapi.model.AdminEinAbResponse;
import ch.it4user.foodsharing.openapi.model.AvailableSlotListResponse;
import ch.it4user.foodsharing.openapi.model.AvailableSlotResponse;
import ch.it4user.foodsharing.openapi.model.AvailableSlotTeacherDetails;
import ch.it4user.foodsharing.openapi.model.BezirkListResponse;
import ch.it4user.foodsharing.openapi.model.BezirkResponse;
import ch.it4user.foodsharing.openapi.model.BookingCommentListResponse;
import ch.it4user.foodsharing.openapi.model.BookingCommentResponse;
import ch.it4user.foodsharing.openapi.model.BookingDetailResponse;
import ch.it4user.foodsharing.openapi.model.BookingListResponse;
import ch.it4user.foodsharing.openapi.model.BookingUserResponse;
import ch.it4user.foodsharing.openapi.model.EinAbResponse;
import ch.it4user.foodsharing.openapi.model.IcalCandidateListResponse;
import ch.it4user.foodsharing.openapi.model.SlotResponse;
import ch.it4user.foodsharing.openapi.model.TeacherEinAbListResponse;
import ch.it4user.foodsharing.openapi.model.TeacherEinAbResponse;
import ch.it4user.foodsharing.openapi.model.TeacherListResponse;
import ch.it4user.foodsharing.openapi.model.TeacherAssignmentOption;
import ch.it4user.foodsharing.openapi.model.TeacherResponse;
import ch.it4user.foodsharing.openapi.model.TeacherSelfResponse;
import ch.it4user.foodsharing.openapi.model.NotificationSubscriptionResponse;
import ch.it4user.foodsharing.openapi.model.NewcomerFunnelResponse;
import ch.it4user.foodsharing.openapi.model.NewcomerFunnelStep;
import ch.it4user.foodsharing.openapi.model.NewcomerFunnelUser;
import ch.it4user.foodsharing.openapi.model.NewcomerNeed;
import ch.it4user.foodsharing.openapi.model.NewcomerNeedKind;
import ch.it4user.foodsharing.openapi.model.NewcomerGap;
import ch.it4user.foodsharing.openapi.model.NewcomerGapSeverity;
import ch.it4user.foodsharing.openapi.model.NewcomerNextStep;
import ch.it4user.foodsharing.openapi.model.NewcomerProgressResponse;
import ch.it4user.foodsharing.openapi.model.NewcomerStage;
import ch.it4user.foodsharing.service.AdminService;
import ch.it4user.foodsharing.service.NewcomerProgressService;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class ApiModelMapper {

    public BezirkResponse toBezirkResponse(Bezirk bezirk) {
        if (bezirk == null) {
            return null;
        }
        BezirkResponse response = new BezirkResponse();
        response.setId(bezirk.getId());
        response.setName(bezirk.getName());
        response.setSlug(bezirk.getSlug());
        response.setPickupEinAbLeadMinutes(bezirk.getPickupEinAbLeadMinutes());
        response.setInfoAvailable(bezirk.hasInfo());
        return response;
    }

    public AdminBezirkSummary toAdminBezirkSummary(Bezirk bezirk) {
        AdminBezirkSummary response = new AdminBezirkSummary();
        response.setId(bezirk.getId());
        response.setName(bezirk.getName());
        response.setSlug(bezirk.getSlug());
        response.setActive(bezirk.isActive());
        return response;
    }

    /** Expects the Bezirke to be loaded with their disabled categories. */
    public BezirkListResponse toBezirkListResponse(List<Bezirk> bezirke) {
        BezirkListResponse response = new BezirkListResponse();
        response.setBezirke(bezirke.stream().map(bezirk -> {
            BezirkResponse item = toBezirkResponse(bezirk);
            item.setDisabledCategories(toApiCategories(bezirk.getDisabledCategories()));
            return item;
        }).toList());
        return response;
    }

    private List<ch.it4user.foodsharing.openapi.model.EinAbCategory> toApiCategories(
            java.util.Collection<ch.it4user.foodsharing.domain.enumtype.EinAbCategory> categories) {
        return categories.stream()
                .sorted()
                .map(category -> ch.it4user.foodsharing.openapi.model.EinAbCategory.fromValue(category.name()))
                .toList();
    }

    public AdminBezirkResponse toAdminBezirkResponse(
            Bezirk bezirk,
            FoodsharingBezirkMessageConnectionService.ConnectionStatus messageConnectionStatus,
            boolean userInfoSourceConfigured) {
        AdminBezirkResponse response = new AdminBezirkResponse();
        response.setId(bezirk.getId());
        response.setName(bezirk.getName());
        response.setSlug(bezirk.getSlug());
        response.setCleaningStoreId(bezirk.getCleaningStoreId());
        response.setPreventDuplicateTeacherBookings(bezirk.isPreventDuplicateTeacherBookings());
        response.setPreventDuplicateCategoryBookings(bezirk.isPreventDuplicateCategoryBookings());
        response.setMaxActiveBookingsPerUser(bezirk.getMaxActiveBookingsPerUser());
        response.setPickupEinAbLeadMinutes(bezirk.getPickupEinAbLeadMinutes());
        response.setRequireFairteilerVisitByThirdEinAb(bezirk.isRequireFairteilerVisitByThirdEinAb());
        response.setUseFoodsharingUserInfoApi(bezirk.isUseFoodsharingUserInfoApi());
        response.setFoodsharingMessagingEmail(messageConnectionStatus.email());
        response.setFoodsharingMessagingConfigured(messageConnectionStatus.configured());
        response.setUserInfoSourceConfigured(userInfoSourceConfigured);
        response.setIgnoredCategories(bezirk.getIgnoredCategories().stream()
                .map(category -> ch.it4user.foodsharing.openapi.model.EinAbCategory.fromValue(category.name()))
                .toList());
        response.setDisabledCategories(toApiCategories(bezirk.getDisabledCategories()));
        response.setInfoAvailable(bezirk.hasInfo());
        response.setInfoStepEnabled(bezirk.isInfoStepEnabled());
        response.setInfoStepOnlyUnverified(bezirk.isInfoStepOnlyUnverified());
        response.setInfoMarkdown(bezirk.getInfoMarkdown() == null ? "" : bezirk.getInfoMarkdown());
        response.setInfoMarkdownEn(bezirk.getInfoMarkdownEn() == null ? "" : bezirk.getInfoMarkdownEn());
        response.setInfoMarkdownGws(bezirk.getInfoMarkdownGws() == null ? "" : bezirk.getInfoMarkdownGws());
        return response;
    }

    public BezirkInfoResponse toBezirkInfoResponse(Bezirk bezirk, ch.it4user.foodsharing.domain.enumtype.LanguageCode language) {
        BezirkInfoResponse response = new BezirkInfoResponse();
        response.setBezirkSlug(bezirk.getSlug());
        response.setBezirkName(bezirk.getName());
        response.setMarkdown(bezirk.infoMarkdown(language));
        return response;
    }

    public TeacherResponse toTeacherResponse(User teacher) {
        TeacherResponse response = new TeacherResponse();
        response.setId(teacher.getId());
        response.setEmail(teacher.getEmail());
        response.setFoodsharingId(teacher.getFoodsharingId());
        response.setName(teacher.getName());
        response.setPhoneNumber(teacher.getPhoneNumber());
        response.setIcalLink(teacher.getIcalLink());
        response.setActive(teacher.isActive());
        response.setCanGiveEinAbs(teacher.isCanGiveEinAbs());
        response.setCanManageUsers(teacher.isCanManageUsers());
        response.setCanUseAutomations(teacher.isCanUseAutomations());
        response.setCanSeeUserPickupCountGrouping(teacher.isCanSeeUserPickupCountGrouping());
        response.setCanSeeAllAutomationDecisions(teacher.isCanSeeAllAutomationDecisions());
        response.setCanManageSurveys(teacher.isCanManageSurveys());
        response.setBezirk(toBezirkResponse(activeHomeBezirk(teacher)));
        response.setLanguage(ch.it4user.foodsharing.openapi.model.Language.fromValue(teacher.getPreferredLanguage().getCode()));
        response.setCreatedAt(toOffsetDateTime(teacher.getCreatedAt()));
        response.setUpdatedAt(toOffsetDateTime(teacher.getUpdatedAt()));
        return response;
    }

    public NotificationSubscriptionResponse toNotificationSubscriptionResponse(NotificationSubscription subscription) {
        NotificationSubscriptionResponse response = new NotificationSubscriptionResponse();
        response.setActive(subscription.isActive());
        response.setBezirk(toBezirkResponse(subscription.getBezirk()));
        return response;
    }

    public TeacherSelfResponse toTeacherSelfResponse(
            User teacher,
            List<ch.it4user.foodsharing.openapi.model.IcalCandidate> candidates,
            String icalFeedLink) {
        TeacherSelfResponse response = new TeacherSelfResponse();
        copyTeacher(toTeacherResponse(teacher), response);
        response.setIcalCandidates(candidates);
        response.setIcalFeedLink(icalFeedLink == null ? null : URI.create(icalFeedLink));
        return response;
    }

    public TeacherListResponse toTeacherListResponse(Page<User> teachers) {
        TeacherListResponse response = new TeacherListResponse();
        response.setTeachers(teachers.getContent().stream().map(this::toTeacherResponse).toList());
        fillPage(response, teachers);
        return response;
    }

    public TeacherAssignmentOption toTeacherAssignmentOption(User teacher) {
        TeacherAssignmentOption response = new TeacherAssignmentOption();
        response.setId(teacher.getId());
        response.setName(teacher.getName());
        response.setPhoneNumber(teacher.getPhoneNumber());
        return response;
    }

    public BookingUserResponse toBookingUserResponse(User bookingUser) {
        BookingUserResponse response = new BookingUserResponse();
        response.setId(bookingUser.getId());
        response.setEmail(bookingUser.getEmail());
        response.setName(bookingUser.getName());
        response.setFoodsharingId(bookingUser.getFoodsharingId());
        response.setPhoneNumber(bookingUser.getPhoneNumber());
        response.setLanguage(ch.it4user.foodsharing.openapi.model.Language.fromValue(bookingUser.getPreferredLanguage().getCode()));
        response.setActive(bookingUser.isActive());
        response.setVerified(bookingUser.isVerified());
        response.setLastVerifiedAt(toOffsetDateTime(bookingUser.getLastVerifiedAt()));
        response.setLastLoginAt(toOffsetDateTime(bookingUser.getLastLoginAt()));
        response.setVerificationMessageSentAt(toOffsetDateTime(bookingUser.getVerificationMessageSentAt()));
        response.setCanGiveEinAbs(bookingUser.isCanGiveEinAbs());
        response.setCanManageUsers(bookingUser.isCanManageUsers());
        response.setCanUseAutomations(bookingUser.isCanUseAutomations());
        response.setCanSeeUserPickupCountGrouping(bookingUser.isCanSeeUserPickupCountGrouping());
        response.setCanSeeAllAutomationDecisions(bookingUser.isCanSeeAllAutomationDecisions());
        response.setCanManageSurveys(bookingUser.isCanManageSurveys());
        response.setBezirk(toBezirkResponse(activeHomeBezirk(bookingUser)));
        response.setCreatedAt(toOffsetDateTime(bookingUser.getCreatedAt()));
        response.setUpdatedAt(toOffsetDateTime(bookingUser.getUpdatedAt()));
        return response;
    }

    public AdminBookingUserPageResponse toAdminBookingUserPageResponse(AdminService.AdminUsersView view) {
        AdminBookingUserPageResponse response = new AdminBookingUserPageResponse();
        response.setUsers(view.users().getContent().stream()
                .map(user -> {
                    AdminBookingUserResponse userResponse = toAdminBookingUserResponse(user, view.bookingsByUser(), view.commentsByUser());
                    userResponse.setReadyToVerify(view.readyToVerifyUserIds().contains(user.getId()));
                    return userResponse;
                })
                .toList());
        fillPage(response, view.users());
        return response;
    }

    public AdminBookingUserResponse toAdminBookingUserResponse(User bookingUser,
                                                               Map<UUID, List<Slot>> bookingsByUser,
                                                               Map<UUID, List<BookingComment>> commentsByUser) {
        AdminBookingUserResponse response = new AdminBookingUserResponse();
        response.setUser(toBookingUserResponse(bookingUser));
        List<Slot> bookings = bookingsByUser.getOrDefault(bookingUser.getId(), List.of());
        response.setBookings(bookings.stream().map(this::toBookingDetailResponse).toList());
        response.setComments(commentsByUser.getOrDefault(bookingUser.getId(), List.of()).stream().map(this::toBookingCommentResponse).toList());
        response.setPickupCount((int) bookings.stream()
                .filter(booking -> booking.getStatus() == SlotStatus.BOOKED || booking.getStatus() == SlotStatus.DONE)
                .count());
        response.setReadyToVerify(false);
        return response;
    }

    public AvailableSlotListResponse toAvailableSlotListResponse(Page<Slot> slots, boolean includeTeacherDetails) {
        AvailableSlotListResponse response = new AvailableSlotListResponse();
        response.setSlots(slots.getContent().stream()
                .map(slot -> toAvailableSlotResponse(slot, includeTeacherDetails))
                .toList());
        fillPage(response, slots);
        return response;
    }

    public AvailableSlotResponse toAvailableSlotResponse(Slot slot, boolean includeTeacherDetails) {
        AvailableSlotResponse response = new AvailableSlotResponse();
        response.setSlotId(slot.getId());
        response.setBezirk(toBezirkResponse(slot.getEinAb().getBezirk()));
        response.setCategory(ch.it4user.foodsharing.openapi.model.EinAbCategory.fromValue(slot.getEinAb().getCategory().name()));
        response.setStartDateTime(toOffsetDateTime(slot.getEinAb().getStartDateTime()));
        response.setLocation(slot.getEinAb().getPublicLocation());
        response.setPublicLocation(slot.getEinAb().getPublicLocation());
        User teacher = slotTeacher(slot);
        response.setTeacherName(teacher.getName());
        response.setTeacherId(teacher.getId());
        response.setMinimumPickupCount(slot.getEinAb().getMinimumPickupCount());
        response.setPublicInfo(slot.getEinAb().getPublicInfo());
        response.setVisitFairteiler(slot.getEinAb().isVisitFairteiler());
        response.setStatus(ch.it4user.foodsharing.openapi.model.SlotStatus.fromValue(slot.getStatus().name()));
        if (includeTeacherDetails) {
            response.setTeacherDetails(toAvailableSlotTeacherDetails(slot, teacher));
        }
        return response;
    }

    private AvailableSlotTeacherDetails toAvailableSlotTeacherDetails(Slot slot, User teacher) {
        EinAb einAb = slot.getEinAb();
        AvailableSlotTeacherDetails details = new AvailableSlotTeacherDetails();
        details.setEinAbId(einAb.getId());
        details.setLocation(einAb.getLocation());
        details.setBusinessName(einAb.getBusinessName());
        details.setPrivateAddress(einAb.getPrivateAddress());
        details.setPublicGeneralLocation(einAb.getPublicGeneralLocation());
        details.setOnlineCallLink(toUri(einAb.getOnlineCallLink()));
        details.setPrivateInfo(einAb.getPrivateInfo());
        details.setTeacherPhoneNumber(teacher.getPhoneNumber());
        details.setSlotCount(einAb.getSlotCount());
        return details;
    }

    public BookingDetailResponse toBookingDetailResponse(Slot slot) {
        BookingDetailResponse response = new BookingDetailResponse();
        response.setSlotId(slot.getId());
        response.setEinAbId(slot.getEinAb().getId());
        response.setBezirk(toBezirkResponse(slot.getEinAb().getBezirk()));
        response.setCategory(ch.it4user.foodsharing.openapi.model.EinAbCategory.fromValue(slot.getEinAb().getCategory().name()));
        response.setStartDateTime(toOffsetDateTime(slot.getEinAb().getStartDateTime()));
        response.setLocation(slot.getEinAb().getLocation());
        response.setPublicLocation(slot.getEinAb().getPublicLocation());
        response.setOnlineCallLink(toUri(slot.getEinAb().getOnlineCallLink()));
        response.setPrivateInfo(slot.getEinAb().getPrivateInfo());
        response.setPublicInfo(slot.getEinAb().getPublicInfo());
        response.setBusinessName(slot.getEinAb().getBusinessName());
        User teacher = slotTeacher(slot);
        response.setTeacherName(teacher.getName());
        response.setTeacherPhoneNumber(teacher.getPhoneNumber());
        response.setVisitFairteiler(slot.getEinAb().isVisitFairteiler());
        response.setStatus(ch.it4user.foodsharing.openapi.model.SlotStatus.fromValue(slot.getStatus().name()));
        if (slot.getBookingUser() != null) {
            response.setBookingUser(toBookingUserResponse(slot.getBookingUser()));
        }
        return response;
    }

    public BookingListResponse toBookingListResponse(Page<Slot> slots) {
        BookingListResponse response = new BookingListResponse();
        response.setBookings(slots.getContent().stream().map(this::toBookingDetailResponse).toList());
        fillPage(response, slots);
        return response;
    }

    public SlotResponse toSlotResponse(Slot slot) {
        SlotResponse response = new SlotResponse();
        response.setId(slot.getId());
        response.setEinAbId(slot.getEinAb().getId());
        User teacher = slotTeacher(slot);
        response.setTeacherId(teacher.getId());
        response.setTeacherName(teacher.getName());
        response.setTeacherPhoneNumber(teacher.getPhoneNumber());
        response.setStatus(ch.it4user.foodsharing.openapi.model.SlotStatus.fromValue(slot.getStatus().name()));
        response.setBookedAt(toOffsetDateTime(slot.getBookedAt()));
        response.setDoneAt(toOffsetDateTime(slot.getDoneAt()));
        response.setCreatedAt(toOffsetDateTime(slot.getCreatedAt()));
        response.setUpdatedAt(toOffsetDateTime(slot.getUpdatedAt()));
        if (slot.getBookingUser() != null) {
            response.setBookingUser(toBookingUserResponse(slot.getBookingUser()));
        }
        return response;
    }

    public EinAbResponse toEinAbResponse(EinAb einAb) {
        EinAbResponse response = new EinAbResponse();
        response.setId(einAb.getId());
        response.setBezirk(toBezirkResponse(einAb.getBezirk()));
        response.setCategory(ch.it4user.foodsharing.openapi.model.EinAbCategory.fromValue(einAb.getCategory().name()));
        response.setStartDateTime(toOffsetDateTime(einAb.getStartDateTime()));
        response.setLocation(einAb.getLocation());
        response.setPublicLocation(einAb.getPublicLocation());
        response.setBusinessName(einAb.getBusinessName());
        response.setPrivateAddress(einAb.getPrivateAddress());
        response.setPublicGeneralLocation(einAb.getPublicGeneralLocation());
        response.setOnlineCallLink(toUri(einAb.getOnlineCallLink()));
        response.setPrivateInfo(einAb.getPrivateInfo());
        response.setPublicInfo(einAb.getPublicInfo());
        response.setTeacher(toTeacherResponse(einAb.getTeacher()));
        response.setVisitFairteiler(einAb.isVisitFairteiler());
        response.setSlotCount(einAb.getSlotCount());
        response.setMinimumPickupCount(einAb.getMinimumPickupCount());
        response.setCreatedAt(toOffsetDateTime(einAb.getCreatedAt()));
        response.setUpdatedAt(toOffsetDateTime(einAb.getUpdatedAt()));
        return response;
    }

    public TeacherEinAbResponse toTeacherEinAbResponse(EinAb einAb, List<Slot> slots) {
        TeacherEinAbResponse response = new TeacherEinAbResponse();
        copyEinAb(toEinAbResponse(einAb), response);
        response.setSlots(slots.stream().map(this::toSlotResponse).toList());
        return response;
    }

    private User slotTeacher(Slot slot) {
        return slot.getTeacher() == null ? slot.getEinAb().getTeacher() : slot.getTeacher();
    }

    public TeacherEinAbListResponse toTeacherEinAbListResponse(Page<TeacherEinAbResponse> einAbs) {
        TeacherEinAbListResponse response = new TeacherEinAbListResponse();
        response.setEinAbs(einAbs.getContent());
        fillPage(response, einAbs);
        return response;
    }

    public AdminEinAbListResponse toAdminEinAbListResponse(Page<EinAb> einAbs, java.util.Map<UUID, List<Slot>> bookedSlotsByEinAb) {
        AdminEinAbListResponse response = new AdminEinAbListResponse();
        response.setEinAbs(einAbs.getContent().stream()
                .map(einAb -> toAdminEinAbResponse(einAb, bookedSlotsByEinAb.getOrDefault(einAb.getId(), List.of())))
                .toList());
        fillPage(response, einAbs);
        return response;
    }

    private AdminEinAbResponse toAdminEinAbResponse(EinAb einAb, List<Slot> bookedSlots) {
        AdminEinAbResponse response = new AdminEinAbResponse();
        copyEinAb(toEinAbResponse(einAb), response);
        response.setHasBookings(!bookedSlots.isEmpty());
        response.setBookings(bookedSlots.stream().map(slot -> {
            ch.it4user.foodsharing.openapi.model.AdminEinAbBooking booking = new ch.it4user.foodsharing.openapi.model.AdminEinAbBooking();
            booking.setSlotId(slot.getId());
            booking.setName(slot.getBookingUser().getName());
            booking.setFoodsharingId(slot.getBookingUser().getFoodsharingId());
            return booking;
        }).toList());
        return response;
    }

    public BookingCommentResponse toBookingCommentResponse(BookingComment comment) {
        BookingCommentResponse response = new BookingCommentResponse();
        response.setId(comment.getId());
        response.setBookingUserId(comment.getBookingUser().getId());
        response.setSlotId(comment.getSlot() == null ? null : comment.getSlot().getId());
        response.setTeacherId(comment.getTeacher().getId());
        response.setTeacherName(comment.getTeacher().getName());
        response.setComment(comment.getComment());
        response.setCreatedAt(toOffsetDateTime(comment.getCreatedAt()));
        return response;
    }

    public BookingCommentListResponse toBookingCommentListResponse(List<BookingComment> comments) {
        BookingCommentListResponse response = new BookingCommentListResponse();
        response.setComments(comments.stream().map(this::toBookingCommentResponse).toList());
        return response;
    }

    public IcalCandidateListResponse toIcalCandidateListResponse(Page<ch.it4user.foodsharing.openapi.model.IcalCandidate> candidates) {
        IcalCandidateListResponse response = new IcalCandidateListResponse();
        response.setCandidates(candidates.getContent());
        fillPage(response, candidates);
        return response;
    }

    /** A disabled home Bezirk counts as none, so the person (or an admin) can choose a new one. */
    private Bezirk activeHomeBezirk(User user) {
        return user.getBezirk() != null && user.getBezirk().isActive() ? user.getBezirk() : null;
    }

    private OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private URI toUri(String value) {
        return value == null ? null : URI.create(value);
    }

    private void fillPage(ch.it4user.foodsharing.openapi.model.TeacherListResponse response, Page<?> page) {
        response.setPage(page.getNumber());
        response.setSize(page.getSize());
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setHasNext(page.hasNext());
        response.setHasPrevious(page.hasPrevious());
    }

    private void fillPage(ch.it4user.foodsharing.openapi.model.AvailableSlotListResponse response, Page<?> page) {
        response.setPage(page.getNumber());
        response.setSize(page.getSize());
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setHasNext(page.hasNext());
        response.setHasPrevious(page.hasPrevious());
    }

    private void fillPage(ch.it4user.foodsharing.openapi.model.BookingListResponse response, Page<?> page) {
        response.setPage(page.getNumber());
        response.setSize(page.getSize());
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setHasNext(page.hasNext());
        response.setHasPrevious(page.hasPrevious());
    }

    private void fillPage(ch.it4user.foodsharing.openapi.model.TeacherEinAbListResponse response, Page<?> page) {
        response.setPage(page.getNumber());
        response.setSize(page.getSize());
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setHasNext(page.hasNext());
        response.setHasPrevious(page.hasPrevious());
    }

    private void fillPage(ch.it4user.foodsharing.openapi.model.AdminEinAbListResponse response, Page<?> page) {
        response.setPage(page.getNumber());
        response.setSize(page.getSize());
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setHasNext(page.hasNext());
        response.setHasPrevious(page.hasPrevious());
    }

    private void fillPage(ch.it4user.foodsharing.openapi.model.IcalCandidateListResponse response, Page<?> page) {
        response.setPage(page.getNumber());
        response.setSize(page.getSize());
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setHasNext(page.hasNext());
        response.setHasPrevious(page.hasPrevious());
    }

    private void fillPage(ch.it4user.foodsharing.openapi.model.AdminBookingUserPageResponse response, Page<?> page) {
        response.setPage(page.getNumber());
        response.setSize(page.getSize());
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        response.setHasNext(page.hasNext());
        response.setHasPrevious(page.hasPrevious());
    }

    private void copyTeacher(TeacherResponse source, TeacherSelfResponse target) {
        target.setId(source.getId());
        target.setEmail(source.getEmail());
        target.setFoodsharingId(source.getFoodsharingId());
        target.setName(source.getName());
        target.setPhoneNumber(source.getPhoneNumber());
        target.setIcalLink(source.getIcalLink());
        target.setActive(source.getActive());
        target.setCanGiveEinAbs(source.getCanGiveEinAbs());
        target.setCanManageUsers(source.getCanManageUsers());
        target.setCanUseAutomations(source.getCanUseAutomations());
        target.setCanSeeUserPickupCountGrouping(source.getCanSeeUserPickupCountGrouping());
        target.setCanSeeAllAutomationDecisions(source.getCanSeeAllAutomationDecisions());
        target.setCanManageSurveys(source.getCanManageSurveys());
        target.setBezirk(source.getBezirk());
        target.setLanguage(source.getLanguage());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    private void copyEinAb(EinAbResponse source, AdminEinAbResponse target) {
        target.setId(source.getId());
        target.setBezirk(source.getBezirk());
        target.setCategory(source.getCategory());
        target.setStartDateTime(source.getStartDateTime());
        target.setLocation(source.getLocation());
        target.setPublicLocation(source.getPublicLocation());
        target.setBusinessName(source.getBusinessName());
        target.setPrivateAddress(source.getPrivateAddress());
        target.setPublicGeneralLocation(source.getPublicGeneralLocation());
        target.setOnlineCallLink(source.getOnlineCallLink());
        target.setPrivateInfo(source.getPrivateInfo());
        target.setPublicInfo(source.getPublicInfo());
        target.setTeacher(source.getTeacher());
        target.setVisitFairteiler(source.getVisitFairteiler());
        target.setSlotCount(source.getSlotCount());
        target.setMinimumPickupCount(source.getMinimumPickupCount());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    private void copyEinAb(EinAbResponse source, TeacherEinAbResponse target) {
        target.setId(source.getId());
        target.setBezirk(source.getBezirk());
        target.setCategory(source.getCategory());
        target.setStartDateTime(source.getStartDateTime());
        target.setLocation(source.getLocation());
        target.setPublicLocation(source.getPublicLocation());
        target.setBusinessName(source.getBusinessName());
        target.setPrivateAddress(source.getPrivateAddress());
        target.setPublicGeneralLocation(source.getPublicGeneralLocation());
        target.setOnlineCallLink(source.getOnlineCallLink());
        target.setPrivateInfo(source.getPrivateInfo());
        target.setPublicInfo(source.getPublicInfo());
        target.setTeacher(source.getTeacher());
        target.setVisitFairteiler(source.getVisitFairteiler());
        target.setSlotCount(source.getSlotCount());
        target.setMinimumPickupCount(source.getMinimumPickupCount());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    public NewcomerProgressResponse toNewcomerProgressResponse(NewcomerProgressService.Progress progress) {
        NewcomerProgressResponse response = new NewcomerProgressResponse();
        response.setTargetEinAbs(progress.targetEinAbs());
        response.setCountedEinAbs(progress.countedEinAbs());
        response.setUpcomingEinAbs(progress.upcomingEinAbs());
        response.setFairteilerRequired(progress.fairteilerRequired());
        response.setFairteilerDone(progress.fairteilerDone());
        response.setFairteilerBooked(progress.fairteilerBooked());
        response.setVerified(progress.user().isVerified());
        response.setStage(NewcomerStage.valueOf(progress.stage().name()));
        response.setNextStep(NewcomerNextStep.valueOf(progress.nextStep().name()));
        response.setDoneCategories(progress.doneCategories().stream()
                .map(category -> ch.it4user.foodsharing.openapi.model.EinAbCategory.valueOf(category.name()))
                .toList());
        response.setBookableCategories(progress.bookableCategories().stream()
                .map(category -> ch.it4user.foodsharing.openapi.model.EinAbCategory.valueOf(category.name()))
                .toList());
        return response;
    }

    public NewcomerFunnelResponse toNewcomerFunnelResponse(NewcomerProgressService.Funnel funnel) {
        NewcomerFunnelResponse response = new NewcomerFunnelResponse();
        response.setTargetEinAbs(funnel.targetEinAbs());
        response.setFairteilerRequired(funnel.fairteilerRequired());
        response.setDays(funnel.days());
        response.setTotalUsers(funnel.totalUsers());
        response.setInTrainingUsers(funnel.inTrainingUsers());
        response.setStalledUsers(funnel.stalledUsers());
        response.setEinAbsStillNeeded(funnel.einAbsStillNeeded());
        response.setOpenSlots(funnel.openSlots());
        response.setFairteilerVisitsStillNeeded(funnel.fairteilerVisitsStillNeeded());
        response.setOpenFairteilerSlots(funnel.openFairteilerSlots());
        response.setGapHorizonDays(funnel.gapHorizonDays());
        response.setNewcomersNeedingEinAbs(funnel.newcomersNeedingEinAbs());
        response.setNewcomersWithoutPlace(funnel.newcomersWithoutPlace());
        response.setWaitingLongDays(funnel.waitingLongDays());
        response.setNeeds(funnel.needs().stream().map(need -> {
            NewcomerNeed item = new NewcomerNeed();
            item.setKind(NewcomerNeedKind.valueOf(need.kind().name()));
            item.setCategory(need.category() == null ? null
                    : ch.it4user.foodsharing.openapi.model.EinAbCategory.valueOf(need.category().name()));
            item.setNewcomers(need.newcomers());
            item.setWithoutPlace(need.withoutPlace());
            item.setWaitingLong(need.waitingLong());
            item.setOpenSlots(need.openSlots());
            item.setSeverity(NewcomerGapSeverity.valueOf(need.severity().name()));
            return item;
        }).toList());
        response.setViewerCategories(funnel.viewerCategories().stream()
                .map(category -> ch.it4user.foodsharing.openapi.model.EinAbCategory.valueOf(category.name()))
                .toList());
        response.setSteps(funnel.steps().stream().map(step -> {
            NewcomerFunnelStep item = new NewcomerFunnelStep();
            item.setStage(NewcomerStage.valueOf(step.stage().name()));
            item.setEinAbCount(step.einAbCount());
            item.setUserCount(step.userCount());
            item.setReachedCount(step.reachedCount());
            item.setStalledCount(step.stalledCount());
            item.setMedianDaysInStep(step.medianDaysInStep());
            item.setUsers(step.users().stream().map(this::toNewcomerFunnelUser).toList());
            return item;
        }).toList());
        response.setGaps(funnel.gaps().stream().map(gap -> {
            NewcomerGap item = new NewcomerGap();
            item.setCategory(ch.it4user.foodsharing.openapi.model.EinAbCategory.valueOf(gap.category().name()));
            item.setWithFairteiler(gap.withFairteiler());
            item.setStudents(gap.students());
            item.setOpenSlots(gap.openSlots());
            item.setSeverity(NewcomerGapSeverity.valueOf(gap.severity().name()));
            return item;
        }).toList());
        return response;
    }

    private NewcomerFunnelUser toNewcomerFunnelUser(NewcomerProgressService.Progress progress) {
        NewcomerFunnelUser user = new NewcomerFunnelUser();
        user.setId(progress.user().getId());
        user.setName(progress.user().getName());
        user.setFoodsharingId(progress.user().getFoodsharingId());
        user.setRegisteredAt(progress.user().getCreatedAt().atOffset(ZoneOffset.UTC));
        user.setCountedEinAbs(progress.countedEinAbs());
        user.setUpcomingEinAbs(progress.upcomingEinAbs());
        user.setFairteilerDone(progress.fairteilerDone());
        user.setLastActivityAt(progress.lastActivityAt().atOffset(ZoneOffset.UTC));
        user.setStalled(progress.stalled());
        return user;
    }
}
