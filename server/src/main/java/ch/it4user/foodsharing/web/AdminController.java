package ch.it4user.foodsharing.web;

import ch.it4user.foodsharing.openapi.api.AdminApi;
import ch.it4user.foodsharing.openapi.model.AdminBookingUserPageResponse;
import ch.it4user.foodsharing.openapi.model.AdminVerificationMessageSentRequest;
import ch.it4user.foodsharing.openapi.model.AdminBookingCountRequest;
import ch.it4user.foodsharing.openapi.model.AdminBezirkResponse;
import ch.it4user.foodsharing.openapi.model.AdminBezirkSummary;
import ch.it4user.foodsharing.openapi.model.AdminBezirkSummaryListResponse;
import ch.it4user.foodsharing.openapi.model.CreateBezirkRequest;
import ch.it4user.foodsharing.openapi.model.BezirkDisableImpactEinAb;
import ch.it4user.foodsharing.openapi.model.BezirkDisableImpactResponse;
import ch.it4user.foodsharing.openapi.model.BezirkDisableImpactUser;
import ch.it4user.foodsharing.openapi.model.AdminEinAbListResponse;
import ch.it4user.foodsharing.openapi.model.DeleteAdminEinAbRequest;
import ch.it4user.foodsharing.openapi.model.AutomationRunSummary;
import ch.it4user.foodsharing.openapi.model.BookingCommentResponse;
import ch.it4user.foodsharing.openapi.model.BookingUserResponse;
import ch.it4user.foodsharing.openapi.model.BulkAddTeacherResult;
import ch.it4user.foodsharing.openapi.model.BulkAddTeachersRequest;
import ch.it4user.foodsharing.openapi.model.BulkAddTeachersResponse;
import ch.it4user.foodsharing.openapi.model.CreateBookingUserRequest;
import ch.it4user.foodsharing.openapi.model.CreateBookingCommentRequest;
import ch.it4user.foodsharing.openapi.model.CreateHistoricalEinAbRequest;
import ch.it4user.foodsharing.openapi.model.FoodsharingAutomationAudit;
import ch.it4user.foodsharing.openapi.model.FoodsharingPickupExtraSlotAudit;
import ch.it4user.foodsharing.openapi.model.FoodsharingMetricCount;
import ch.it4user.foodsharing.openapi.model.FoodsharingMetricDailyValue;
import ch.it4user.foodsharing.openapi.model.FoodsharingMetricsOverview;
import ch.it4user.foodsharing.openapi.model.FoodsharingPickupCoverage;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreCoverage;
import ch.it4user.foodsharing.openapi.model.FoodsharingCleaningRuleExemption;
import ch.it4user.foodsharing.openapi.model.FoodsharingCleaningRuleExemptionRequest;
import ch.it4user.foodsharing.openapi.model.FoodsharingConnectRequest;
import ch.it4user.foodsharing.openapi.model.FoodsharingConnectionStatus;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreInfoCancellation;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreInfoSettings;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreInfoSettingsRequest;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreInfoTestMessageRequest;
import ch.it4user.foodsharing.service.FoodsharingStoreInfoService;
import ch.it4user.foodsharing.openapi.model.FoodsharingExtraAutomationAudit;
import ch.it4user.foodsharing.openapi.model.FoodsharingExtraAutomationOverview;
import ch.it4user.foodsharing.openapi.model.FoodsharingFuturePickup;
import ch.it4user.foodsharing.openapi.model.FoodsharingFuturePickupUser;
import ch.it4user.foodsharing.openapi.model.FoodsharingOpenSlotAdvertisementAutomation;
import ch.it4user.foodsharing.openapi.model.FoodsharingOpenSlotAdvertisementAutomationRequest;
import ch.it4user.foodsharing.openapi.model.FoodsharingOpenSlotAdvertisementOverview;
import ch.it4user.foodsharing.openapi.model.FoodsharingRequestAutomation;
import ch.it4user.foodsharing.openapi.model.FoodsharingRequestAutomationRequest;
import ch.it4user.foodsharing.openapi.model.FoodsharingRequestAutomationOverview;
import ch.it4user.foodsharing.openapi.model.FoodsharingRunRequest;
import ch.it4user.foodsharing.openapi.model.FoodsharingRunResult;
import ch.it4user.foodsharing.openapi.model.FoodsharingGapStoreGroup;
import ch.it4user.foodsharing.openapi.model.FoodsharingManagedStore;
import ch.it4user.foodsharing.openapi.model.FoodsharingTelegramBotTokenRequest;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreAutomationOverview;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreAutomation;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreAutomationRequest;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreAutomationLinkedStore;
import ch.it4user.foodsharing.openapi.model.FoodsharingLinkedStoreExemption;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreAutomationIncomingLinkedStore;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreAutomationExcludedDate;
import ch.it4user.foodsharing.openapi.model.FoodsharingStoreAutomationExcludedDateRequest;
import ch.it4user.foodsharing.openapi.model.TeacherListResponse;
import ch.it4user.foodsharing.openapi.model.TeacherResponse;
import ch.it4user.foodsharing.openapi.model.SlotResponse;
import ch.it4user.foodsharing.openapi.model.TelegramChat;
import ch.it4user.foodsharing.openapi.model.FoodsharingMessagingTelegramRequest;
import ch.it4user.foodsharing.openapi.model.FoodsharingMessagingTelegramStatus;
import ch.it4user.foodsharing.openapi.model.TelegramTestMessageRequest;
import ch.it4user.foodsharing.openapi.model.UserPermissionsRequest;
import ch.it4user.foodsharing.openapi.model.UpdateBezirkRequest;
import ch.it4user.foodsharing.openapi.model.UpdateUserBezirkRequest;
import ch.it4user.foodsharing.openapi.model.TeacherEinAbResponse;
import ch.it4user.foodsharing.domain.entity.Bezirk;
import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.domain.enumtype.UserPermission;
import ch.it4user.foodsharing.service.AdminService;
import ch.it4user.foodsharing.service.ApiErrorCode;
import ch.it4user.foodsharing.service.ApiException;
import ch.it4user.foodsharing.service.BezirkService;
import ch.it4user.foodsharing.service.CurrentActorService;
import ch.it4user.foodsharing.service.FoodsharingPickupAutomationService;
import ch.it4user.foodsharing.service.FoodsharingBezirkMessageConnectionService;
import ch.it4user.foodsharing.service.FoodsharingMessageTelegramService;
import ch.it4user.foodsharing.service.FoodsharingMyPickupsService;
import ch.it4user.foodsharing.service.FoodsharingMetricsService;
import ch.it4user.foodsharing.service.FoodsharingPickupAutomationService.StoreUpcomingPickupsView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import ch.it4user.foodsharing.openapi.model.NewcomerFunnelResponse;
import ch.it4user.foodsharing.service.NewcomerProgressService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController implements AdminApi {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminController.class);

    private final AdminService adminService;
    private final ApiModelMapper mapper;
    private final FoodsharingPickupAutomationService foodsharingPickupAutomationService;
    private final CurrentActorService currentActorService;
    private final BezirkService bezirkService;
    private final FoodsharingBezirkMessageConnectionService messageConnectionService;
    private final ch.it4user.foodsharing.repository.SlotRepository slotRepository;
    private final FoodsharingStoreInfoService storeInfoService;
    private final FoodsharingMessageTelegramService messageTelegramService;
    private final FoodsharingMyPickupsService myPickupsService;
    private final FoodsharingMetricsService metricsService;
    private final ch.it4user.foodsharing.service.FoodsharingClient foodsharingClient;
    private final NewcomerProgressService newcomerProgressService;

    public AdminController(AdminService adminService,
                           ApiModelMapper mapper,
                           FoodsharingPickupAutomationService foodsharingPickupAutomationService,
                           CurrentActorService currentActorService,
                           BezirkService bezirkService,
                           FoodsharingBezirkMessageConnectionService messageConnectionService,
                           ch.it4user.foodsharing.repository.SlotRepository slotRepository,
                           FoodsharingStoreInfoService storeInfoService,
                           FoodsharingMessageTelegramService messageTelegramService,
                           FoodsharingMyPickupsService myPickupsService,
                           FoodsharingMetricsService metricsService,
                           ch.it4user.foodsharing.service.FoodsharingClient foodsharingClient,
                           NewcomerProgressService newcomerProgressService) {
        this.foodsharingClient = foodsharingClient;
        this.newcomerProgressService = newcomerProgressService;
        this.myPickupsService = myPickupsService;
        this.metricsService = metricsService;
        this.storeInfoService = storeInfoService;
        this.messageTelegramService = messageTelegramService;
        this.adminService = adminService;
        this.mapper = mapper;
        this.foodsharingPickupAutomationService = foodsharingPickupAutomationService;
        this.currentActorService = currentActorService;
        this.bezirkService = bezirkService;
        this.messageConnectionService = messageConnectionService;
        this.slotRepository = slotRepository;
    }

    @Override
    public ResponseEntity<TeacherListResponse> getAdminTeachers(String bezirkSlug, Integer page, Integer size) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toTeacherListResponse(
                adminService.getTeachers(bezirkSlug, page == null ? 0 : page, size == null ? 20 : size)));
    }

    @Override
    public ResponseEntity<TeacherListResponse> getAdminAdmins(Integer page, Integer size) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toTeacherListResponse(adminService.getAdmins(page == null ? 0 : page, size == null ? 20 : size)));
    }

    @Override
    public ResponseEntity<TeacherResponse> enableAdminTeacher(UUID teacherId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toTeacherResponse(adminService.setTeacherActive(teacherId, true)));
    }

    @Override
    public ResponseEntity<TeacherResponse> disableAdminTeacher(UUID teacherId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toTeacherResponse(adminService.setTeacherActive(teacherId, false)));
    }

    @Override
    public ResponseEntity<TeacherResponse> grantAdminTeacher(UUID teacherId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toTeacherResponse(adminService.setTeacherAdmin(teacherId, true)));
    }

    @Override
    public ResponseEntity<TeacherResponse> revokeAdminTeacher(UUID teacherId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toTeacherResponse(adminService.setTeacherAdmin(teacherId, false)));
    }

    @Override
    public ResponseEntity<AdminBookingUserPageResponse> getAdminUsers(
            String bezirkSlug,
            String search,
            Integer page,
            Integer size,
            Boolean threePickupsOnly,
            Boolean activeOnly,
            Boolean unverifiedOnly,
            Boolean unassigned,
            Boolean allBezirke) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? 50 : size;
        boolean resolvedThreePickupsOnly = threePickupsOnly != null && threePickupsOnly;
        boolean resolvedActiveOnly = activeOnly == null || activeOnly;
        return ResponseEntity.ok(mapper.toAdminBookingUserPageResponse(
                adminService.getUsers(
                        bezirkSlug,
                        search,
                        Boolean.TRUE.equals(unassigned),
                        Boolean.TRUE.equals(allBezirke),
                        resolvedPage,
                        resolvedSize,
                        resolvedThreePickupsOnly,
                        resolvedActiveOnly,
                        unverifiedOnly == null || unverifiedOnly)));
    }

    @Override
    public ResponseEntity<ch.it4user.foodsharing.openapi.model.BookingUserOptionListResponse> searchAdminBookingUsers(
            String bezirkSlug, String query) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        ch.it4user.foodsharing.openapi.model.BookingUserOptionListResponse response =
                new ch.it4user.foodsharing.openapi.model.BookingUserOptionListResponse();
        response.setUsers(adminService.searchBookingUsers(bezirkSlug, query).stream()
                .map(user -> {
                    ch.it4user.foodsharing.openapi.model.BookingUserOption option =
                            new ch.it4user.foodsharing.openapi.model.BookingUserOption();
                    option.setId(user.getId());
                    option.setName(user.getName());
                    option.setFoodsharingId(user.getFoodsharingId());
                    return option;
                })
                .toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<SlotResponse> setAdminBookingCounted(
            String bezirkSlug,
            UUID slotId,
            AdminBookingCountRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toSlotResponse(
                adminService.setBookingCounted(bezirkSlug, slotId, Boolean.TRUE.equals(request.getCounted()))));
    }

    @Override
    public ResponseEntity<AdminEinAbListResponse> getAdminEinAbs(String bezirkSlug, Integer page, Integer size) {
        currentActorService.requireAdmin();
        org.springframework.data.domain.Page<ch.it4user.foodsharing.domain.entity.EinAb> einAbs = adminService.getEinAbs(
                bezirkSlug, page == null ? 0 : page, size == null ? 20 : size);
        return ResponseEntity.ok(mapper.toAdminEinAbListResponse(
                einAbs, adminService.findBookedSlotsByEinAb(einAbs.getContent())));
    }

    @Override
    public ResponseEntity<Void> deleteAdminEinAb(String bezirkSlug, UUID einAbId, DeleteAdminEinAbRequest request) {
        adminService.deleteEinAb(bezirkSlug, currentActorService.requireAdmin(), einAbId,
                request == null ? null : request.getReason());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> removeAdminSlotBooking(String bezirkSlug, UUID slotId) {
        adminService.removeBooking(bezirkSlug, currentActorService.requireAdmin(), slotId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<TeacherEinAbResponse> createAdminHistoricalEinAb(
            String bezirkSlug, UUID bookingUserId, CreateHistoricalEinAbRequest request) {
        User admin = currentActorService.requireAdmin();
        ch.it4user.foodsharing.domain.entity.EinAb einAb = adminService.createHistoricalEinAb(
                bezirkSlug, admin, bookingUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toTeacherEinAbResponse(
                einAb, slotRepository.findAllByEinAbOrderByCreatedAtAsc(einAb)));
    }

    @Override
    public ResponseEntity<BookingCommentResponse> addAdminSlotComment(
            String bezirkSlug,
            UUID slotId,
            CreateBookingCommentRequest request) {
        User admin = currentActorService.requireAdmin();
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toBookingCommentResponse(
                adminService.addBookingComment(bezirkSlug, slotId, admin, request.getComment())));
    }

    @Override
    public ResponseEntity<AdminBezirkSummaryListResponse> getAdminBezirke() {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(new AdminBezirkSummaryListResponse().bezirke(
                bezirkService.findAllForAdmin().stream().map(mapper::toAdminBezirkSummary).toList()));
    }

    @Override
    public ResponseEntity<AdminBezirkSummary> createAdminBezirk(CreateBezirkRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toAdminBezirkSummary(
                bezirkService.create(request.getName(), request.getSlug())));
    }

    @Override
    public ResponseEntity<AdminBezirkSummary> enableAdminBezirk(String bezirkSlug) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toAdminBezirkSummary(bezirkService.setActive(bezirkSlug, true)));
    }

    @Override
    public ResponseEntity<BezirkDisableImpactResponse> getAdminBezirkDisableImpact(String bezirkSlug) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        BezirkService.DisableImpact impact = bezirkService.disableImpact(bezirkSlug);
        return ResponseEntity.ok(new BezirkDisableImpactResponse()
                .assignedUserCount(impact.assignedUserCount())
                .assignedUsers(impact.assignedUsers().stream().map(this::toImpactUser).toList())
                .upcomingEinAbCount((int) impact.upcomingEinAbCount())
                .upcomingEinAbs(impact.upcomingEinAbs().stream().map(einAb -> new BezirkDisableImpactEinAb()
                        .id(einAb.getId())
                        .startDateTime(einAb.getStartDateTime().atOffset(java.time.ZoneOffset.UTC))
                        .category(ch.it4user.foodsharing.openapi.model.EinAbCategory.fromValue(einAb.getCategory().name()))
                        .businessName(einAb.getBusinessName())
                        .teacherName(einAb.getTeacher() == null ? null : einAb.getTeacher().getName())
                        .bookedUsers(impact.bookedUsersByEinAbId().getOrDefault(einAb.getId(), List.of()).stream()
                                .map(this::toImpactUser).toList()))
                        .toList()));
    }

    private BezirkDisableImpactUser toImpactUser(User user) {
        return new BezirkDisableImpactUser().id(user.getId()).name(user.getName()).foodsharingId(user.getFoodsharingId());
    }

    @Override
    public ResponseEntity<AdminBezirkSummary> disableAdminBezirk(String bezirkSlug) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toAdminBezirkSummary(bezirkService.setActive(bezirkSlug, false)));
    }

    @Override
    public ResponseEntity<AdminBezirkResponse> getAdminBezirk(String bezirkSlug) {
        User automationUser = null;
        if (!currentActorService.hasPermission(UserPermission.CAN_MANAGE_USERS)) {
            automationUser = currentActorService.requireAutomationUser();
        }
        Bezirk bezirk = bezirkService.requireActive(bezirkSlug);
        if (automationUser != null && (automationUser.getBezirk() == null
                || !automationUser.getBezirk().getId().equals(bezirk.getId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.BEZIRK_NOT_FOUND);
        }
        return ResponseEntity.ok(mapper.toAdminBezirkResponse(bezirk, messageConnectionService.status(bezirk),
                foodsharingClient.hasUserInfoSource(bezirk)));
    }

    @Override
    public ResponseEntity<AdminBezirkResponse> updateAdminBezirk(
            String bezirkSlug,
            UpdateBezirkRequest updateBezirkRequest) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        Bezirk bezirk = bezirkService.updateSettings(
                        bezirkSlug,
                        updateBezirkRequest.getCleaningStoreId(),
                        updateBezirkRequest.getPreventDuplicateTeacherBookings(),
                        updateBezirkRequest.getPreventDuplicateCategoryBookings(),
                        updateBezirkRequest.getMaxActiveBookingsPerUser(),
                        updateBezirkRequest.getPickupEinAbLeadMinutes(),
                        updateBezirkRequest.getRequireFairteilerVisitByThirdEinAb(),
                        updateBezirkRequest.getIgnoredCategories() == null ? null : updateBezirkRequest.getIgnoredCategories().stream()
                                .map(value -> ch.it4user.foodsharing.domain.enumtype.EinAbCategory.valueOf(value.getValue()))
                                .collect(java.util.stream.Collectors.toSet()),
                        updateBezirkRequest.getDisabledCategories() == null ? null : updateBezirkRequest.getDisabledCategories().stream()
                                .map(value -> ch.it4user.foodsharing.domain.enumtype.EinAbCategory.valueOf(value.getValue()))
                                .collect(java.util.stream.Collectors.toSet()),
                        updateBezirkRequest.getUseFoodsharingUserInfoApi(),
                        updateBezirkRequest.getFoodsharingMessagingEmail(),
                        updateBezirkRequest.getFoodsharingMessagingPassword(),
                        updateBezirkRequest.getInfoStepEnabled(),
                        updateBezirkRequest.getInfoStepOnlyUnverified(),
                        updateBezirkRequest.getInfoMarkdown(),
                        updateBezirkRequest.getInfoMarkdownEn(),
                        updateBezirkRequest.getInfoMarkdownGws());
        return ResponseEntity.ok(mapper.toAdminBezirkResponse(bezirk, messageConnectionService.status(bezirk),
                foodsharingClient.hasUserInfoSource(bezirk)));
    }

    @Override
    public ResponseEntity<FoodsharingMessagingTelegramStatus> getFoodsharingMessagingTelegram(String bezirkSlug) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(toMessagingTelegramStatus(messageTelegramService.status(bezirkService.requireActive(bezirkSlug))));
    }

    @Override
    public ResponseEntity<FoodsharingMessagingTelegramStatus> updateFoodsharingMessagingTelegram(
            String bezirkSlug,
            FoodsharingMessagingTelegramRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(toMessagingTelegramStatus(messageTelegramService.update(
                bezirkService.requireActive(bezirkSlug),
                request.getTelegramBotToken(),
                request.getTelegramChatId(),
                request.getTelegramChatTitle(),
                Boolean.TRUE.equals(request.getEnabled()))));
    }

    @Override
    public ResponseEntity<Void> deleteFoodsharingMessagingTelegram(String bezirkSlug) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        messageTelegramService.remove(bezirkService.requireActive(bezirkSlug));
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<TelegramChat>> getFoodsharingMessagingTelegramChats(String bezirkSlug) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(messageTelegramService.chats(bezirkService.requireActive(bezirkSlug)).stream()
                .map(chat -> {
                    TelegramChat response = new TelegramChat();
                    response.setId(chat.id());
                    response.setTitle(chat.title());
                    response.setType(chat.type());
                    return response;
                })
                .toList());
    }

    @Override
    public ResponseEntity<Void> sendFoodsharingMessagingTelegramTestMessage(String bezirkSlug) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        messageTelegramService.sendTestMessage(bezirkService.requireActive(bezirkSlug));
        return ResponseEntity.noContent().build();
    }

    private FoodsharingMessagingTelegramStatus toMessagingTelegramStatus(FoodsharingMessageTelegramService.Status status) {
        FoodsharingMessagingTelegramStatus response = new FoodsharingMessagingTelegramStatus();
        response.setAccountEmail(status.accountEmail());
        response.setFallbackAccount(status.fallbackAccount());
        response.setTelegramBotTokenConfigured(status.telegramBotTokenConfigured());
        response.setEnabled(status.enabled());
        response.setTelegramChatId(status.telegramChatId());
        response.setTelegramChatTitle(status.telegramChatTitle());
        return response;
    }

    @Override
    public ResponseEntity<BookingUserResponse> enableAdminBookingUser(UUID bookingUserId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toBookingUserResponse(adminService.enableBookingUser(bookingUserId)));
    }

    @Override
    public ResponseEntity<BookingUserResponse> disableAdminBookingUser(UUID bookingUserId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toBookingUserResponse(adminService.disableBookingUser(bookingUserId)));
    }

    @Override
    public ResponseEntity<BookingUserResponse> verifyAdminBookingUser(UUID bookingUserId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toBookingUserResponse(adminService.verifyBookingUser(bookingUserId)));
    }

    @Override
    public ResponseEntity<BookingUserResponse> unverifyAdminBookingUser(UUID bookingUserId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toBookingUserResponse(adminService.unverifyBookingUser(bookingUserId)));
    }

    @Override
    public ResponseEntity<BookingUserResponse> setAdminBookingUserVerificationMessageSent(
            UUID bookingUserId,
            AdminVerificationMessageSentRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toBookingUserResponse(
                adminService.setVerificationMessageSent(bookingUserId, Boolean.TRUE.equals(request.getSent()))));
    }

    @Override
    public ResponseEntity<BookingUserResponse> refreshAdminBookingUser(UUID bookingUserId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toBookingUserResponse(adminService.refreshBookingUser(bookingUserId)));
    }

    @Override
    public ResponseEntity<BookingUserResponse> grantAdminBookingUser(UUID bookingUserId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toBookingUserResponse(adminService.grantBookingUserAdmin(bookingUserId)));
    }

    @Override
    public ResponseEntity<BookingUserResponse> createAdminBookingUser(CreateBookingUserRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toBookingUserResponse(
                adminService.createBookingUser(request.getFoodsharingId(), request.getBezirkSlug())));
    }

    @Override
    public ResponseEntity<BulkAddTeachersResponse> bulkAddAdminTeachers(BulkAddTeachersRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        String bezirkSlug = request.getBezirkSlug();
        java.util.LinkedHashSet<String> foodsharingIds = new java.util.LinkedHashSet<>();
        for (String foodsharingId : request.getFoodsharingIds()) {
            if (foodsharingId != null && !foodsharingId.isBlank()) {
                foodsharingIds.add(foodsharingId.trim());
            }
        }
        BulkAddTeachersResponse response = new BulkAddTeachersResponse();
        response.setResults(new java.util.ArrayList<>());
        java.util.List<User> existingTeachers = new java.util.ArrayList<>();
        // One transaction per ID, so a failing ID does not undo the others.
        for (String foodsharingId : foodsharingIds) {
            BulkAddTeacherResult result = new BulkAddTeacherResult();
            result.setFoodsharingId(foodsharingId);
            try {
                AdminService.TeacherGrantResult granted = adminService.addTeacherByFoodsharingId(foodsharingId, bezirkSlug);
                result.setStatus(granted.created() ? BulkAddTeacherResult.StatusEnum.CREATED
                        : granted.alreadyTeacher() ? BulkAddTeacherResult.StatusEnum.ALREADY_TEACHER
                        : BulkAddTeacherResult.StatusEnum.GRANTED);
                result.setUserId(granted.user().getId());
                result.setName(granted.user().getName());
                if (!granted.created()) {
                    existingTeachers.add(granted.user());
                }
            } catch (ApiException exception) {
                result.setStatus(BulkAddTeacherResult.StatusEnum.FAILED);
                result.setErrorCode(exception.getCode().name());
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound exception) {
                result.setStatus(BulkAddTeacherResult.StatusEnum.FAILED);
                result.setErrorCode(ApiErrorCode.ACCOUNT_NOT_FOUND.name());
            } catch (RuntimeException exception) {
                log.warn("Could not add teacher foodsharingId={}", foodsharingId, exception);
                result.setStatus(BulkAddTeacherResult.StatusEnum.FAILED);
                result.setErrorCode(ApiErrorCode.UNEXPECTED_ERROR.name());
            }
            response.getResults().add(result);
        }
        response.setRefreshQueued(adminService.queueTeacherRefresh(bezirkSlug, existingTeachers));
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<TeacherResponse> enableAdminUser(UUID adminUserId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toTeacherResponse(adminService.setAdminActive(adminUserId, true)));
    }

    @Override
    public ResponseEntity<TeacherResponse> disableAdminUser(UUID adminUserId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toTeacherResponse(adminService.setAdminActive(adminUserId, false)));
    }

    @Override
    public ResponseEntity<TeacherResponse> revokeAdminUser(UUID adminUserId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toTeacherResponse(adminService.setAdminAdmin(adminUserId, false)));
    }


    @Override
    public ResponseEntity<TeacherResponse> setAdminUserPermissions(UUID userId, UserPermissionsRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        AdminService.UserPermissions permissions = new AdminService.UserPermissions(
                Boolean.TRUE.equals(request.getCanGiveEinAbs()),
                Boolean.TRUE.equals(request.getCanManageUsers()),
                Boolean.TRUE.equals(request.getCanUseAutomations()),
                Boolean.TRUE.equals(request.getCanSeeUserPickupCountGrouping()),
                Boolean.TRUE.equals(request.getCanSeeAllAutomationDecisions()),
                Boolean.TRUE.equals(request.getCanManageSurveys()));
        return ResponseEntity.ok(mapper.toTeacherResponse(adminService.setUserPermissions(userId, permissions)));
    }

    @Override
    public ResponseEntity<BookingUserResponse> updateAdminUserBezirk(UUID userId, UpdateUserBezirkRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toBookingUserResponse(
                adminService.setUserBezirk(userId, request == null ? null : request.getBezirkSlug())));
    }

    @Override
    public ResponseEntity<FoodsharingConnectionStatus> connectFoodsharing(FoodsharingConnectRequest request) {
        currentActorService.requireAutomationUser();
        return ResponseEntity.ok(toFoodsharingConnectionStatus(
                foodsharingPickupAutomationService.connect(request.getEmail(), request.getPassword(), request.getTelegramBotToken())));
    }

    @Override
    public ResponseEntity<Void> disconnectFoodsharing() {
        currentActorService.requireAutomationUser();
        foodsharingPickupAutomationService.disconnect();
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> saveFoodsharingTelegramBotToken(FoodsharingTelegramBotTokenRequest request) {
        currentActorService.requireAutomationUser();
        foodsharingPickupAutomationService.saveTelegramBotToken(request.getTelegramBotToken());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<FoodsharingConnectionStatus> getFoodsharingStatus() {
        currentActorService.requireAutomationUser();
        return ResponseEntity.ok(toFoodsharingConnectionStatus(foodsharingPickupAutomationService.status()));
    }

    @Override
    public ResponseEntity<FoodsharingStoreAutomationOverview> getFoodsharingStores(String bezirkSlug) {
        currentActorService.requireAutomationUser();
        FoodsharingPickupAutomationService.StoreOverviewView overview = foodsharingPickupAutomationService.stores(bezirkSlug);
        FoodsharingStoreAutomationOverview response = new FoodsharingStoreAutomationOverview();
        response.setAutomations(overview.automations().stream()
                .map(this::toFoodsharingStoreAutomation)
                .toList());
        response.setAvailableStores(overview.availableStores().stream()
                .map(this::toFoodsharingManagedStore)
                .toList());
        response.setLinkedStoreDefaultMessage(overview.linkedStoreDefaultMessage());
        response.setManagedStoreIds(overview.managedStoreIds());
        response.setGapStoreGroups(overview.gapStoreGroups().stream().map(group -> {
            FoodsharingGapStoreGroup gapStoreGroup = new FoodsharingGapStoreGroup();
            gapStoreGroup.setMinimumGapDays(group.minimumGapDays());
            gapStoreGroup.setStores(toManagedStores(group.stores()));
            return gapStoreGroup;
        }).toList());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<FoodsharingStoreAutomation> saveFoodsharingStoreAutomation(
            String bezirkSlug,
            Long storeId,
            FoodsharingStoreAutomationRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        FoodsharingPickupAutomationService.StoreAutomationRequest serviceRequest =
                new FoodsharingPickupAutomationService.StoreAutomationRequest(
                        request.getStoreName(),
                        Boolean.TRUE.equals(request.getEnabled()),
                        request.getDryRunEnabled() == null || Boolean.TRUE.equals(request.getDryRunEnabled()),
                        Boolean.TRUE.equals(request.getGapRuleEnabled()),
                        request.getMinimumGapDays() == null ? 0 : request.getMinimumGapDays(),
                        Boolean.TRUE.equals(request.getCleaningRuleEnabled()),
                        Boolean.TRUE.equals(request.getExperienceRuleEnabled()),
                        Boolean.TRUE.equals(request.getPublicHolidayRuleEnabled()),
                        request.getLinkedStores() == null ? null : request.getLinkedStores().stream()
                                .map(link -> new FoodsharingPickupAutomationService.LinkedStoreRuleRequest(
                                        link.getLinkedStores() == null ? List.of() : link.getLinkedStores().stream()
                                                .map(linkedStore -> new FoodsharingPickupAutomationService.LinkedStoreRef(
                                                        linkedStore.getStoreId() == null ? 0 : linkedStore.getStoreId(), linkedStore.getStoreName()))
                                                .toList(),
                                        Boolean.TRUE.equals(link.getBidirectional()),
                                        link.getWindowMode() == null ? null : link.getWindowMode().getValue(),
                                        link.getWindowDaysBefore() == null ? 0 : link.getWindowDaysBefore(),
                                        link.getWindowDaysAfter() == null ? 0 : link.getWindowDaysAfter(),
                                        link.getGraceMinutes() == null ? 0 : link.getGraceMinutes(),
                                        link.getMessage(),
                                        link.getExemptions() == null ? List.of() : link.getExemptions().stream()
                                                .map(exemption -> new FoodsharingPickupAutomationService.LinkedStoreExemption(exemption.getFoodsharingId(), exemption.getReason()))
                                                .toList()))
                                .toList(),
                        request.getGapStores() == null ? null : request.getGapStores().stream()
                                .map(store -> new FoodsharingPickupAutomationService.LinkedStoreRef(store.getStoreId() == null ? 0 : store.getStoreId(), store.getStoreName()))
                                .toList(),
                        request.getGapStoresMinimumDays());
        return ResponseEntity.ok(toFoodsharingStoreAutomation(
                foodsharingPickupAutomationService.save(bezirkSlug, storeId, serviceRequest)));
    }

    @Override
    public ResponseEntity<Void> deleteFoodsharingStoreAutomation(String bezirkSlug, Long storeId) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        foodsharingPickupAutomationService.delete(bezirkSlug, storeId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<FoodsharingRunResult> runFoodsharingAutomation(String bezirkSlug, FoodsharingRunRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(toFoodsharingRunResult(
                foodsharingPickupAutomationService.run(bezirkSlug, Boolean.TRUE.equals(request.getDryRun()))));
    }

    @Override
    public ResponseEntity<FoodsharingRequestAutomationOverview> getFoodsharingRequestAutomationOverview(String bezirkSlug) {
        currentActorService.requireAutomationUser();
        return ResponseEntity.ok(toFoodsharingRequestAutomationOverview(foodsharingPickupAutomationService.requestAutomationOverview(bezirkSlug)));
    }

    @Override
    public ResponseEntity<FoodsharingOpenSlotAdvertisementOverview> getFoodsharingOpenSlotAdvertisementOverview(String bezirkSlug) {
        currentActorService.requireAutomationUser();
        return ResponseEntity.ok(toFoodsharingOpenSlotAdvertisementOverview(foodsharingPickupAutomationService.openSlotAdvertisementOverview(bezirkSlug)));
    }

    @Override
    public ResponseEntity<FoodsharingExtraAutomationOverview> getFoodsharingExtraAutomationOverview(String bezirkSlug) {
        currentActorService.requireAutomationUser();
        return ResponseEntity.ok(toFoodsharingExtraAutomationOverview(foodsharingPickupAutomationService.extraAutomationOverview(bezirkSlug)));
    }

    @Override
    public ResponseEntity<List<FoodsharingExtraAutomationAudit>> getFoodsharingRequestAutomationAudit(String bezirkSlug, Boolean onlyMine) {
        requireAuditAccess(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(foodsharingPickupAutomationService.requestAutomationAudit(bezirkSlug, Boolean.TRUE.equals(onlyMine)).stream()
                .map(this::toFoodsharingExtraAutomationAudit)
                .toList());
    }

    @Override
    public ResponseEntity<List<FoodsharingExtraAutomationAudit>> getFoodsharingOpenSlotAdvertisementAudit(String bezirkSlug, Boolean onlyMine) {
        requireAuditAccess(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(foodsharingPickupAutomationService.openSlotAdvertisementAudit(bezirkSlug, Boolean.TRUE.equals(onlyMine)).stream()
                .map(this::toFoodsharingExtraAutomationAudit)
                .toList());
    }

    @Override
    public ResponseEntity<List<FoodsharingExtraAutomationAudit>> getFoodsharingOpenSlotAdvertisementSentAudit(String bezirkSlug, Boolean onlyMine) {
        requireAuditAccess(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(foodsharingPickupAutomationService.openSlotAdvertisementSentAudit(bezirkSlug, Boolean.TRUE.equals(onlyMine)).stream()
                .map(this::toFoodsharingExtraAutomationAudit)
                .toList());
    }

    @Override
    public ResponseEntity<List<FoodsharingExtraAutomationAudit>> getFoodsharingOpenSlotAdvertisementPlannedNotifications(String bezirkSlug, Boolean onlyMine) {
        requireAuditAccess(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(foodsharingPickupAutomationService.plannedOpenSlotAdvertisementNotifications(bezirkSlug, Boolean.TRUE.equals(onlyMine)).stream()
                .map(this::toFoodsharingExtraAutomationAudit)
                .toList());
    }

    @Override
    public ResponseEntity<List<FoodsharingExtraAutomationAudit>> getFoodsharingExtraAutomationAudit(String bezirkSlug, Boolean onlyMine) {
        currentActorService.requireAutomationUser();
        return ResponseEntity.ok(foodsharingPickupAutomationService.extraAutomationAudit(bezirkSlug, Boolean.TRUE.equals(onlyMine)).stream()
                .map(this::toFoodsharingExtraAutomationAudit)
                .toList());
    }

    @Override
    public ResponseEntity<List<TelegramChat>> getFoodsharingTelegramChats() {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(foodsharingPickupAutomationService.telegramChats().stream()
                .map(this::toTelegramChat)
                .toList());
    }

    @Override
    public ResponseEntity<AutomationRunSummary> runFoodsharingRequestAutomationDryRun() {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(toAutomationRunSummary(foodsharingPickupAutomationService.runRequestAutomations(true)));
    }

    @Override
    public ResponseEntity<AutomationRunSummary> runFoodsharingOpenSlotAdvertisementDryRun() {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(toAutomationRunSummary(foodsharingPickupAutomationService.runAdvertisementAutomations(true)));
    }

    @Override
    public ResponseEntity<Void> sendFoodsharingTelegramTestMessage(TelegramTestMessageRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        foodsharingPickupAutomationService.sendTelegramTestMessage(request.getChatId(), request.getMessage());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<FoodsharingRequestAutomation> saveFoodsharingRequestAutomation(Long storeId, FoodsharingRequestAutomationRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        FoodsharingPickupAutomationService.RequestAutomationRequest serviceRequest =
                new FoodsharingPickupAutomationService.RequestAutomationRequest(
                        request.getStoreName(),
                        Boolean.TRUE.equals(request.getEnabled()),
                        Boolean.TRUE.equals(request.getDryRunEnabled()),
                        Boolean.TRUE.equals(request.getDistanceRuleEnabled()),
                        request.getMaximumDistanceKm());
        return ResponseEntity.ok(toFoodsharingRequestAutomation(
                foodsharingPickupAutomationService.saveRequestAutomation(storeId, serviceRequest)));
    }

    @Override
    public ResponseEntity<Void> deleteFoodsharingRequestAutomation(Long storeId) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        foodsharingPickupAutomationService.deleteRequestAutomation(storeId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<FoodsharingOpenSlotAdvertisementAutomation> saveFoodsharingOpenSlotAdvertisementAutomation(
            Long storeId,
            Integer advertNumber,
            FoodsharingOpenSlotAdvertisementAutomationRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        FoodsharingPickupAutomationService.AdvertisementAutomationRequest serviceRequest =
                new FoodsharingPickupAutomationService.AdvertisementAutomationRequest(
                        request.getStoreName(),
                        Boolean.TRUE.equals(request.getEnabled()),
                        request.getDryRunEnabled() == null || Boolean.TRUE.equals(request.getDryRunEnabled()),
                        request.getTriggerHoursBefore() == null ? 0 : request.getTriggerHoursBefore(),
                        Boolean.TRUE.equals(request.getSendToStoreChat()),
                        Boolean.TRUE.equals(request.getSendToTelegram()),
                        request.getTelegramChatId(),
                        request.getStoreMessages() == null ? List.of() : request.getStoreMessages(),
                        request.getTelegramMessages() == null ? List.of() : request.getTelegramMessages(),
                        request.getLateCancellationMessage(),
                        Boolean.TRUE.equals(request.getSendLateCancellationMessage()),
                        request.getLateCancellationMessageMaximumHoursBeforePickup() == null ? 72 : request.getLateCancellationMessageMaximumHoursBeforePickup(),
                        Boolean.TRUE.equals(request.getSendLatestAdvertisementAfterLateCancellation()));
        return ResponseEntity.ok(toFoodsharingOpenSlotAdvertisementAutomation(
                foodsharingPickupAutomationService.saveAdvertisementAutomation(storeId, advertNumber, serviceRequest)));
    }

    @Override
    public ResponseEntity<Void> deleteFoodsharingOpenSlotAdvertisementAutomation(Long storeId, Integer advertNumber) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        foodsharingPickupAutomationService.deleteAdvertisementAutomation(storeId, advertNumber);
        return ResponseEntity.noContent().build();
    }


    @Override
    public ResponseEntity<List<FoodsharingCleaningRuleExemption>> getFoodsharingCleaningRuleExemptions(String bezirkSlug) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(foodsharingPickupAutomationService.cleaningRuleExemptions(bezirkSlug).stream()
                .map(this::toFoodsharingCleaningRuleExemption)
                .toList());
    }

    @Override
    public ResponseEntity<FoodsharingCleaningRuleExemption> saveFoodsharingCleaningRuleExemption(
            String bezirkSlug,
            FoodsharingCleaningRuleExemptionRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        FoodsharingPickupAutomationService.CleaningRuleExemptionRequest serviceRequest =
                new FoodsharingPickupAutomationService.CleaningRuleExemptionRequest(request.getFoodsharingId(), request.getReason());
        return ResponseEntity.ok(toFoodsharingCleaningRuleExemption(
                foodsharingPickupAutomationService.saveCleaningRuleExemption(bezirkSlug, serviceRequest)));
    }

    @Override
    public ResponseEntity<Void> deleteFoodsharingCleaningRuleExemption(String bezirkSlug, UUID exemptionId) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        foodsharingPickupAutomationService.deleteCleaningRuleExemption(bezirkSlug, exemptionId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<FoodsharingStoreAutomationExcludedDate>> getFoodsharingStoreAutomationExcludedDates(String bezirkSlug) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(foodsharingPickupAutomationService.excludedDates(bezirkSlug).stream()
                .map(this::toFoodsharingStoreAutomationExcludedDate)
                .toList());
    }

    @Override
    public ResponseEntity<FoodsharingStoreAutomationExcludedDate> saveFoodsharingStoreAutomationExcludedDate(
            String bezirkSlug,
            FoodsharingStoreAutomationExcludedDateRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(toFoodsharingStoreAutomationExcludedDate(foodsharingPickupAutomationService.saveExcludedDate(
                bezirkSlug,
                new FoodsharingPickupAutomationService.ExcludedDateRequest(request.getStoreId(), request.getExcludedDate()))));
    }

    @Override
    public ResponseEntity<Void> deleteFoodsharingStoreAutomationExcludedDate(String bezirkSlug, UUID excludedDateId) {
        currentActorService.requirePermission(UserPermission.CAN_USE_AUTOMATIONS);
        foodsharingPickupAutomationService.deleteExcludedDate(bezirkSlug, excludedDateId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<FoodsharingAutomationAudit>> getFoodsharingAutomationAudit(String bezirkSlug, Boolean onlyMine, Boolean excludeConfirmed) {
        requireAuditAccess(UserPermission.CAN_USE_AUTOMATIONS);
        return ResponseEntity.ok(foodsharingPickupAutomationService.audit(bezirkSlug, Boolean.TRUE.equals(onlyMine), Boolean.TRUE.equals(excludeConfirmed)).stream()
                .map(this::toFoodsharingAutomationAudit)
                .toList());
    }

    @Override
    public ResponseEntity<List<FoodsharingFuturePickupUser>> getFoodsharingFuturePickupUsers(String bezirkSlug, Boolean forceRefresh) {
        currentActorService.requirePermission(UserPermission.CAN_SEE_USER_PICKUP_COUNT_GROUPING);
        return ResponseEntity.ok(foodsharingPickupAutomationService.futurePickupUsers(bezirkSlug, Boolean.TRUE.equals(forceRefresh)).stream()
                .map(this::toFoodsharingFuturePickupUser)
                .toList());
    }

    @Override
    public ResponseEntity<List<FoodsharingPickupExtraSlotAudit>> getFoodsharingPickupExtraSlotAudit(String bezirkSlug) {
        requireAuditAccess(UserPermission.CAN_SEE_USER_PICKUP_COUNT_GROUPING);
        return ResponseEntity.ok(myPickupsService.extraSlotAudit(bezirkSlug).stream()
                .map(this::toFoodsharingPickupExtraSlotAudit)
                .toList());
    }

    @Override
    public ResponseEntity<NewcomerFunnelResponse> getAdminNewcomerFunnel(String bezirkSlug, Integer days) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(mapper.toNewcomerFunnelResponse(
                newcomerProgressService.getFunnel(bezirkSlug, days, true, currentActorService.findCurrentUser())));
    }

    @Override
    public ResponseEntity<FoodsharingMetricsOverview> getFoodsharingMetrics(String bezirkSlug, Integer days) {
        requireAuditAccess(UserPermission.CAN_SEE_USER_PICKUP_COUNT_GROUPING);
        FoodsharingMetricsService.MetricsView view = metricsService.metrics(bezirkSlug, days);
        FoodsharingMetricsOverview response = new FoodsharingMetricsOverview();
        response.setPeriodDays(view.periodDays());
        response.setFromDay(view.fromDay());
        response.setToDay(view.toDay());
        response.setCounts(view.totals().entrySet().stream().map(entry -> {
            FoodsharingMetricCount count = new FoodsharingMetricCount();
            count.setKey(entry.getKey());
            count.setValue(entry.getValue());
            return count;
        }).toList());
        response.setDaily(view.daily().stream().map(value -> {
            FoodsharingMetricDailyValue daily = new FoodsharingMetricDailyValue();
            daily.setDay(value.day());
            daily.setKey(value.key());
            daily.setValue(value.value());
            return daily;
        }).toList());
        response.setAdvertsWaiting(view.advertsWaiting());
        FoodsharingMetricsService.CoverageView coverage = view.coverage();
        FoodsharingPickupCoverage coverageResponse = new FoodsharingPickupCoverage();
        coverageResponse.setFrom(toOffsetDateTime(coverage.from()));
        coverageResponse.setTo(toOffsetDateTime(coverage.to()));
        coverageResponse.setPickups(coverage.pickups());
        coverageResponse.setPickupsWithOpenSlots(coverage.pickupsWithOpenSlots());
        coverageResponse.setTotalSlots(coverage.totalSlots());
        coverageResponse.setOccupiedSlots(coverage.occupiedSlots());
        coverageResponse.setOldestRefreshAt(toOffsetDateTime(coverage.oldestRefreshAt()));
        coverageResponse.setStores(coverage.stores().stream().map(this::toFoodsharingStoreCoverage).toList());
        response.setCoverage(coverageResponse);
        return ResponseEntity.ok(response);
    }

    private FoodsharingStoreCoverage toFoodsharingStoreCoverage(StoreUpcomingPickupsView store) {
        FoodsharingStoreCoverage response = new FoodsharingStoreCoverage();
        response.setStoreId(store.storeId());
        response.setStoreName(store.storeName());
        response.setPickups(store.pickups());
        response.setPickupsWithOpenSlots(store.pickupsWithOpenSlots());
        response.setTotalSlots(store.totalSlots());
        response.setOccupiedSlots(store.occupiedSlots());
        response.setRefreshedAt(toOffsetDateTime(store.refreshedAt()));
        return response;
    }

    private FoodsharingPickupExtraSlotAudit toFoodsharingPickupExtraSlotAudit(FoodsharingMyPickupsService.ExtraSlotAuditView audit) {
        FoodsharingPickupExtraSlotAudit response = new FoodsharingPickupExtraSlotAudit();
        response.setStoreId(audit.storeId());
        response.setStoreName(audit.storeName());
        response.setPickupDate(toOffsetDateTime(audit.pickupDate()));
        response.setFoodsharingUserId(audit.foodsharingUserId());
        response.setFoodsharingUserName(audit.foodsharingUserName());
        response.setPreviousTotalSlots(audit.previousTotalSlots());
        response.setNewTotalSlots(audit.newTotalSlots());
        response.setCreatedAt(toOffsetDateTime(audit.createdAt()));
        response.setFilled(audit.filled());
        return response;
    }

    private void requireAuditAccess(UserPermission automationPermission) {
        if (!currentActorService.hasPermission(UserPermission.CAN_SEE_ALL_AUTOMATION_DECISIONS)) {
            currentActorService.requirePermission(automationPermission);
        }
    }

    private FoodsharingConnectionStatus toFoodsharingConnectionStatus(FoodsharingPickupAutomationService.ConnectionStatus status) {
        FoodsharingConnectionStatus response = new FoodsharingConnectionStatus();
        response.setConnected(status.connected());
        response.setEmail(status.email());
        response.setFoodsharingUserId(status.foodsharingUserId());
        response.setAuthenticatedAt(toOffsetDateTime(status.authenticatedAt()));
        response.setTelegramBotTokenConfigured(status.telegramBotTokenConfigured());
        response.setAutomationEnabled(status.automationEnabled());
        response.setAutomationDryRun(status.automationDryRun());
        return response;
    }

    private FoodsharingStoreAutomation toFoodsharingStoreAutomation(FoodsharingPickupAutomationService.StoreAutomationView store) {
        FoodsharingStoreAutomation response = new FoodsharingStoreAutomation();
        response.setStoreId(store.storeId());
        response.setStoreName(store.storeName());
        response.setEnabled(store.enabled());
        response.setDryRunEnabled(store.dryRunEnabled());
        response.setGapRuleEnabled(store.gapRuleEnabled());
        response.setMinimumGapDays(store.minimumGapDays());
        response.setCleaningRuleEnabled(store.cleaningRuleEnabled());
        response.setExperienceRuleEnabled(store.experienceRuleEnabled());
        response.setPublicHolidayRuleEnabled(store.publicHolidayRuleEnabled());
        response.setEditable(store.editable());
        response.setOwnerName(store.ownerName());
        response.setOwnerEmail(store.ownerEmail());
        response.setLinkedStores(store.linkedStores().stream().map(link -> {
            FoodsharingStoreAutomationLinkedStore linkedStore = new FoodsharingStoreAutomationLinkedStore();
            linkedStore.setLinkedStores(link.linkedStores().stream().map(ref -> {
                FoodsharingManagedStore managedStore = new FoodsharingManagedStore();
                managedStore.setStoreId(ref.storeId());
                managedStore.setStoreName(ref.storeName());
                return managedStore;
            }).collect(java.util.stream.Collectors.toList()));
            linkedStore.setBidirectional(link.bidirectional());
            linkedStore.setWindowMode(FoodsharingStoreAutomationLinkedStore.WindowModeEnum.fromValue(link.windowMode()));
            linkedStore.setWindowDaysBefore(link.windowDaysBefore());
            linkedStore.setWindowDaysAfter(link.windowDaysAfter());
            linkedStore.setGraceMinutes(link.graceMinutes());
            linkedStore.setMessage(link.message());
            linkedStore.setExemptions(link.exemptions().stream().map(entry -> {
                FoodsharingLinkedStoreExemption exemption = new FoodsharingLinkedStoreExemption();
                exemption.setFoodsharingId(entry.foodsharingId());
                exemption.setReason(entry.reason());
                return exemption;
            }).collect(java.util.stream.Collectors.toList()));
            return linkedStore;
        }).collect(java.util.stream.Collectors.toList()));
        response.setGapStores(toManagedStores(store.gapStores()));
        response.setGapStoresMinimumDays(store.gapStoresMinimumDays());
        response.setIncomingLinkedStores(store.incomingLinkedStores().stream().map(link -> {
            FoodsharingStoreAutomationIncomingLinkedStore incoming = new FoodsharingStoreAutomationIncomingLinkedStore();
            incoming.setSourceStoreId(link.sourceStoreId());
            incoming.setSourceStoreName(link.sourceStoreName());
            incoming.setBidirectional(link.bidirectional());
            incoming.setWindowMode(FoodsharingStoreAutomationIncomingLinkedStore.WindowModeEnum.fromValue(link.windowMode()));
            incoming.setWindowDaysBefore(link.windowDaysBefore());
            incoming.setWindowDaysAfter(link.windowDaysAfter());
            incoming.setGraceMinutes(link.graceMinutes());
            return incoming;
        }).collect(java.util.stream.Collectors.toList()));
        return response;
    }

    private List<FoodsharingManagedStore> toManagedStores(List<FoodsharingPickupAutomationService.LinkedStoreRef> stores) {
        return stores.stream().map(ref -> {
            FoodsharingManagedStore managedStore = new FoodsharingManagedStore();
            managedStore.setStoreId(ref.storeId());
            managedStore.setStoreName(ref.storeName());
            return managedStore;
        }).collect(java.util.stream.Collectors.toList());
    }

    private FoodsharingManagedStore toFoodsharingManagedStore(FoodsharingPickupAutomationService.ManagedStoreView store) {
        FoodsharingManagedStore response = new FoodsharingManagedStore();
        response.setStoreId(store.storeId());
        response.setStoreName(store.storeName());
        return response;
    }

    private FoodsharingRunResult toFoodsharingRunResult(ch.it4user.foodsharing.service.FoodsharingPickupModels.RunResult result) {
        FoodsharingRunResult response = new FoodsharingRunResult();
        response.setEvaluated(result.evaluated());
        response.setConfirmed(result.confirmed());
        response.setDeclined(result.declined());
        response.setFailed(result.failed());
        response.setDryRun(result.dryRun());
        response.setMessages(result.messages());
        return response;
    }

    private FoodsharingRequestAutomationOverview toFoodsharingRequestAutomationOverview(List<FoodsharingPickupAutomationService.RequestAutomationView> requests) {
        FoodsharingRequestAutomationOverview response = new FoodsharingRequestAutomationOverview();
        response.setRequestAutomations(requests.stream()
                .map(this::toFoodsharingRequestAutomation)
                .toList());
        return response;
    }

    private FoodsharingOpenSlotAdvertisementOverview toFoodsharingOpenSlotAdvertisementOverview(List<FoodsharingPickupAutomationService.AdvertisementAutomationView> advertisements) {
        FoodsharingOpenSlotAdvertisementOverview response = new FoodsharingOpenSlotAdvertisementOverview();
        response.setAdvertisementAutomations(advertisements.stream()
                .map(this::toFoodsharingOpenSlotAdvertisementAutomation)
                .toList());
        return response;
    }

    private FoodsharingExtraAutomationOverview toFoodsharingExtraAutomationOverview(FoodsharingPickupAutomationService.ExtraAutomationOverviewView overview) {
        FoodsharingExtraAutomationOverview response = new FoodsharingExtraAutomationOverview();
        response.setRequestAutomations(overview.requestAutomations().stream()
                .map(this::toFoodsharingRequestAutomation)
                .toList());
        response.setAdvertisementAutomations(overview.advertisementAutomations().stream()
                .map(this::toFoodsharingOpenSlotAdvertisementAutomation)
                .toList());
        return response;
    }

    private FoodsharingRequestAutomation toFoodsharingRequestAutomation(FoodsharingPickupAutomationService.RequestAutomationView requestAutomation) {
        FoodsharingRequestAutomation response = new FoodsharingRequestAutomation();
        response.setStoreId(requestAutomation.storeId());
        response.setStoreName(requestAutomation.storeName());
        response.setEnabled(requestAutomation.enabled());
        response.setDryRunEnabled(requestAutomation.dryRunEnabled());
        response.setDistanceRuleEnabled(requestAutomation.distanceRuleEnabled());
        response.setMaximumDistanceKm(requestAutomation.maximumDistanceKm());
        response.setEditable(requestAutomation.editable());
        response.setOwnerName(requestAutomation.ownerName());
        response.setOwnerEmail(requestAutomation.ownerEmail());
        return response;
    }

    private FoodsharingOpenSlotAdvertisementAutomation toFoodsharingOpenSlotAdvertisementAutomation(FoodsharingPickupAutomationService.AdvertisementAutomationView advertisement) {
        FoodsharingOpenSlotAdvertisementAutomation response = new FoodsharingOpenSlotAdvertisementAutomation();
        response.setStoreId(advertisement.storeId());
        response.setStoreName(advertisement.storeName());
        response.setAdvertNumber(advertisement.advertNumber());
        response.setEnabled(advertisement.enabled());
        response.setDryRunEnabled(advertisement.dryRunEnabled());
        response.setTriggerHoursBefore(advertisement.triggerHoursBefore());
        response.setSendToStoreChat(advertisement.sendToStoreChat());
        response.setSendToTelegram(advertisement.sendToTelegram());
        response.setTelegramChatId(advertisement.telegramChatId());
        response.setStoreMessages(advertisement.storeMessages());
        response.setTelegramMessages(advertisement.telegramMessages());
        response.setLateCancellationMessage(advertisement.lateCancellationMessage());
        response.setSendLateCancellationMessage(advertisement.sendLateCancellationMessage());
        response.setLateCancellationMessageMaximumHoursBeforePickup(advertisement.lateCancellationMessageMaximumHoursBeforePickup());
        response.setSendLatestAdvertisementAfterLateCancellation(advertisement.sendLatestAdvertisementAfterLateCancellation());
        response.setEditable(advertisement.editable());
        response.setOwnerName(advertisement.ownerName());
        response.setOwnerEmail(advertisement.ownerEmail());
        return response;
    }

    private FoodsharingExtraAutomationAudit toFoodsharingExtraAutomationAudit(FoodsharingPickupAutomationService.ExtraAutomationAuditView audit) {
        FoodsharingExtraAutomationAudit response = new FoodsharingExtraAutomationAudit();
        response.setAutomationType(audit.automationType());
        response.setStoreId(audit.storeId());
        response.setStoreName(audit.storeName());
        response.setPickupDate(toOffsetDateTime(audit.pickupDate()));
        response.setNotificationDate(toOffsetDateTime(audit.notificationDate()));
        response.setFoodsharingUserId(audit.foodsharingUserId());
        response.setFoodsharingUserName(audit.foodsharingUserName());
        response.setDryRun(audit.dryRun());
        response.setStatus(audit.status());
        response.setReason(audit.reason());
        response.setMessage(audit.message());
        response.setError(audit.error());
        response.setCreatedAt(toOffsetDateTime(audit.createdAt()));
        return response;
    }

    @Override
    public ResponseEntity<FoodsharingStoreInfoSettings> getFoodsharingStoreInfoSettings() {
        return ResponseEntity.ok(toFoodsharingStoreInfoSettings(storeInfoService.settings()));
    }

    @Override
    public ResponseEntity<FoodsharingStoreInfoSettings> saveFoodsharingStoreInfoSettings(FoodsharingStoreInfoSettingsRequest request) {
        FoodsharingStoreInfoService.SettingsRequest serviceRequest = new FoodsharingStoreInfoService.SettingsRequest(
                Boolean.TRUE.equals(request.getEnabled()),
                Boolean.TRUE.equals(request.getCutoffEnabled()),
                request.getMaximumHoursBeforePickup() == null ? 72 : request.getMaximumHoursBeforePickup(),
                request.getTelegramChatIds() == null ? List.of() : request.getTelegramChatIds(),
                request.getStores() == null ? List.of() : request.getStores().stream()
                        .map(store -> new FoodsharingStoreInfoService.StoreRef(store.getStoreId() == null ? 0 : store.getStoreId(), store.getStoreName()))
                        .toList());
        return ResponseEntity.ok(toFoodsharingStoreInfoSettings(storeInfoService.save(serviceRequest)));
    }

    @Override
    public ResponseEntity<Void> sendFoodsharingStoreInfoTestMessage(FoodsharingStoreInfoTestMessageRequest request) {
        storeInfoService.sendTestMessage(request.getTelegramChatIds());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<FoodsharingStoreInfoCancellation>> getFoodsharingStoreInfoCancellations() {
        return ResponseEntity.ok(storeInfoService.recentCancellations().stream().map(this::toFoodsharingStoreInfoCancellation).toList());
    }

    private FoodsharingStoreInfoSettings toFoodsharingStoreInfoSettings(FoodsharingStoreInfoService.SettingsView view) {
        FoodsharingStoreInfoSettings response = new FoodsharingStoreInfoSettings();
        response.setEnabled(view.enabled());
        response.setCutoffEnabled(view.cutoffEnabled());
        response.setMaximumHoursBeforePickup(view.maximumHoursBeforePickup());
        response.setTelegramChatIds(new java.util.ArrayList<>(view.telegramChatIds()));
        response.setStores(view.stores().stream().map(this::toFoodsharingManagedStore).collect(java.util.stream.Collectors.toList()));
        response.setAvailableStores(view.availableStores().stream().map(this::toFoodsharingManagedStore).collect(java.util.stream.Collectors.toList()));
        response.setTelegramBotTokenConfigured(view.telegramBotTokenConfigured());
        response.setActiveSince(toOffsetDateTime(view.activeSince()));
        return response;
    }

    private FoodsharingManagedStore toFoodsharingManagedStore(FoodsharingStoreInfoService.StoreRef store) {
        FoodsharingManagedStore response = new FoodsharingManagedStore();
        response.setStoreId(store.storeId());
        response.setStoreName(store.storeName() == null ? "" : store.storeName());
        return response;
    }

    private FoodsharingStoreInfoCancellation toFoodsharingStoreInfoCancellation(FoodsharingStoreInfoService.CancellationView view) {
        FoodsharingStoreInfoCancellation response = new FoodsharingStoreInfoCancellation();
        response.setStoreId(view.storeId());
        response.setStoreName(view.storeName());
        response.setPickupDate(toOffsetDateTime(view.pickupDate()));
        response.setFoodsharingUserId(view.foodsharingUserId());
        response.setFoodsharingUserName(view.foodsharingUserName());
        response.setCancelledAt(toOffsetDateTime(view.cancelledAt()));
        response.setHoursBeforePickup(view.hoursBeforePickup());
        response.setTotalSlots(view.totalSlots());
        response.setOccupiedSlots(view.occupiedSlots());
        response.setStatus(view.status());
        response.setImmediateNotifiedAt(toOffsetDateTime(view.immediateNotifiedAt()));
        response.setFinalNotifiedAt(toOffsetDateTime(view.finalNotifiedAt()));
        response.setDryRun(view.dryRun());
        response.setError(view.error());
        return response;
    }

    private TelegramChat toTelegramChat(FoodsharingPickupAutomationService.TelegramChatView chat) {
        TelegramChat response = new TelegramChat();
        response.setId(chat.id());
        response.setTitle(chat.title());
        response.setType(chat.type());
        return response;
    }

    private AutomationRunSummary toAutomationRunSummary(FoodsharingPickupAutomationService.AutomationRunSummary summary) {
        AutomationRunSummary response = new AutomationRunSummary();
        response.setEvaluated(summary.evaluated());
        response.setActed(summary.acted());
        response.setSkipped(summary.skipped());
        response.setDryRun(summary.dryRun());
        response.setMessages(summary.messages());
        return response;
    }


    private FoodsharingCleaningRuleExemption toFoodsharingCleaningRuleExemption(FoodsharingPickupAutomationService.CleaningRuleExemptionView exemption) {
        FoodsharingCleaningRuleExemption response = new FoodsharingCleaningRuleExemption();
        response.setId(exemption.id());
        response.setFoodsharingId(exemption.foodsharingId());
        response.setReason(exemption.reason());
        return response;
    }

    private FoodsharingStoreAutomationExcludedDate toFoodsharingStoreAutomationExcludedDate(FoodsharingPickupAutomationService.ExcludedDateView excludedDate) {
        FoodsharingStoreAutomationExcludedDate response = new FoodsharingStoreAutomationExcludedDate();
        response.setId(excludedDate.id());
        response.setStoreId(excludedDate.storeId());
        response.setStoreName(excludedDate.storeName());
        response.setExcludedDate(excludedDate.excludedDate());
        response.setEventName(excludedDate.eventName());
        response.setManual(excludedDate.manual());
        return response;
    }

    private FoodsharingAutomationAudit toFoodsharingAutomationAudit(FoodsharingPickupAutomationService.AuditView audit) {
        FoodsharingAutomationAudit response = new FoodsharingAutomationAudit();
        response.setStoreId(audit.storeId());
        response.setStoreName(audit.storeName());
        response.setFoodsharingUserId(audit.foodsharingUserId());
        response.setFoodsharingUserName(audit.foodsharingUserName());
        response.setPickupDate(toOffsetDateTime(audit.pickupDate()));
        response.setDryRun(audit.dryRun());
        response.setDecision(audit.decision());
        response.setReasons(audit.reasons());
        response.setUserMessage(audit.userMessage());
        response.setError(audit.error());
        response.setCreatedAt(toOffsetDateTime(audit.createdAt()));
        return response;
    }

    private FoodsharingFuturePickupUser toFoodsharingFuturePickupUser(FoodsharingPickupAutomationService.StorePickupUserView user) {
        FoodsharingFuturePickupUser response = new FoodsharingFuturePickupUser();
        response.setFoodsharingUserId(user.foodsharingUserId());
        response.setName(user.name());
        response.setFuturePickupCount(user.futurePickupCount());
        response.setFuturePickups(user.futurePickups().stream()
                .map(this::toFoodsharingFuturePickup)
                .toList());
        return response;
    }

    private FoodsharingFuturePickup toFoodsharingFuturePickup(FoodsharingPickupAutomationService.StorePickupView pickup) {
        FoodsharingFuturePickup response = new FoodsharingFuturePickup();
        response.setStoreId(pickup.storeId());
        response.setStoreName(pickup.storeName());
        response.setPickupDate(toOffsetDateTime(pickup.pickupDate()));
        response.setConfirmed(pickup.confirmed());
        response.setValidationErrors(pickup.validationErrors());
        return response;
    }

    private OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

}
