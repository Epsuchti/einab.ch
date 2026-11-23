package ch.it4user.foodsharing.web;

import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.openapi.api.UserApi;
import ch.it4user.foodsharing.openapi.model.AddPickupExtraSlotRequest;
import ch.it4user.foodsharing.openapi.model.BookSlotRequest;
import ch.it4user.foodsharing.openapi.model.BookingDetailResponse;
import ch.it4user.foodsharing.openapi.model.BookingListResponse;
import ch.it4user.foodsharing.openapi.model.MyPickupListResponse;
import ch.it4user.foodsharing.openapi.model.MyPickupResponse;
import ch.it4user.foodsharing.openapi.model.MyPickupStoreResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import ch.it4user.foodsharing.openapi.model.BookingUserResponse;
import ch.it4user.foodsharing.openapi.model.UpdateMyProfileRequest;
import java.util.UUID;
import ch.it4user.foodsharing.openapi.model.Language;
import ch.it4user.foodsharing.openapi.model.NewcomerProgressResponse;
import ch.it4user.foodsharing.openapi.model.NotificationStatusResponse;
import ch.it4user.foodsharing.openapi.model.NotificationSubscribeRequest;
import ch.it4user.foodsharing.openapi.model.NotificationSubscriptionResponse;
import ch.it4user.foodsharing.service.BezirkService;
import ch.it4user.foodsharing.service.CurrentActorService;
import ch.it4user.foodsharing.openapi.model.BezirkInfoResponse;
import ch.it4user.foodsharing.service.NewcomerProgressService;
import ch.it4user.foodsharing.service.NotificationService;
import ch.it4user.foodsharing.service.PublicService;
import ch.it4user.foodsharing.service.PushNotificationService;
import ch.it4user.foodsharing.domain.enumtype.PushPlatform;
import ch.it4user.foodsharing.openapi.model.PushDeviceRequest;
import ch.it4user.foodsharing.openapi.model.PushDeviceUnregisterRequest;
import ch.it4user.foodsharing.service.FoodsharingMyPickupsService;
import ch.it4user.foodsharing.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController implements UserApi {

    private final UserService userService;
    private final CurrentActorService currentActorService;
    private final ApiModelMapper mapper;
    private final FoodsharingMyPickupsService myPickupsService;
    private final NewcomerProgressService newcomerProgressService;
    private final NotificationService notificationService;
    private final PublicService publicService;
    private final PushNotificationService pushNotificationService;
    private final BezirkService bezirkService;

    public UserController(UserService userService, CurrentActorService currentActorService, ApiModelMapper mapper,
            FoodsharingMyPickupsService myPickupsService, NewcomerProgressService newcomerProgressService,
            NotificationService notificationService, PublicService publicService,
            PushNotificationService pushNotificationService, BezirkService bezirkService) {
        this.bezirkService = bezirkService;
        this.pushNotificationService = pushNotificationService;
        this.publicService = publicService;
        this.notificationService = notificationService;
        this.newcomerProgressService = newcomerProgressService;
        this.userService = userService;
        this.currentActorService = currentActorService;
        this.mapper = mapper;
        this.myPickupsService = myPickupsService;
    }

    @Override
    public ResponseEntity<BezirkInfoResponse> getBezirkInfo(String bezirkSlug, String language) {
        currentActorService.requireFoodsharingId();
        return ResponseEntity.ok(mapper.toBezirkInfoResponse(bezirkService.requireActive(bezirkSlug),
                ch.it4user.foodsharing.domain.enumtype.LanguageCode.fromCode(language)));
    }

    @Override
    public ResponseEntity<BookingDetailResponse> bookSlot(String bezirkSlug, UUID slotId, BookSlotRequest bookSlotRequest) {
        return ResponseEntity.ok(mapper.toBookingDetailResponse(
                publicService.bookSlot(
                        bezirkSlug,
                        slotId,
                        currentActorService.requireFoodsharingId(),
                        ch.it4user.foodsharing.domain.enumtype.LanguageCode.fromCode(
                                bookSlotRequest.getLanguage() == null ? "de" : bookSlotRequest.getLanguage().getValue()))));
    }

    @Override
    public ResponseEntity<BookingListResponse> getMyBookings(String bezirkSlug, Integer page, Integer size) {
        return ResponseEntity.ok(mapper.toBookingListResponse(
                userService.getBookingsByFoodsharingId(bezirkSlug, currentActorService.requireFoodsharingId(),
                        page == null ? 0 : page, size == null ? 20 : size)));
    }

    @Override
    public ResponseEntity<NewcomerProgressResponse> getMyProgress(String bezirkSlug) {
        return ResponseEntity.ok(mapper.toNewcomerProgressResponse(
                newcomerProgressService.getMyProgress(bezirkSlug, currentActorService.requireFoodsharingId())));
    }

    @Override
    public ResponseEntity<NotificationStatusResponse> getMyNotificationStatus() {
        User user = userService.getProfileByFoodsharingId(currentActorService.requireFoodsharingId());
        NotificationStatusResponse response = new NotificationStatusResponse();
        var subscription = notificationService.findMine();
        response.setActive(subscription.map(value -> value.isActive()).orElse(false));
        subscription.ifPresent(value -> {
            response.setChannel(ch.it4user.foodsharing.openapi.model.NotificationChannel.fromValue(value.getChannel().name()));
            response.setEmail(value.getDeliveryEmail());
        });
        response.setVerified(user.isVerified());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<NotificationSubscriptionResponse> subscribeMyNotifications(
            String bezirkSlug, NotificationSubscribeRequest request) {
        Language language = request.getLanguage();
        return ResponseEntity.ok(mapper.toNotificationSubscriptionResponse(notificationService.subscribe(
                bezirkSlug,
                ch.it4user.foodsharing.domain.enumtype.LanguageCode.fromCode(language == null ? "de" : language.getValue()),
                request.getChannel() == null ? null
                        : ch.it4user.foodsharing.domain.enumtype.NotificationChannel.valueOf(request.getChannel().getValue()),
                request.getEmail())));
    }

    @Override
    public ResponseEntity<Void> unsubscribeMyNotifications() {
        notificationService.unsubscribeMine();
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> registerMyPushDevice(PushDeviceRequest pushDeviceRequest) {
        pushNotificationService.registerDevice(currentActorService.requireFoodsharingId(), pushDeviceRequest.getToken(),
                PushPlatform.valueOf(pushDeviceRequest.getPlatform().getValue()));
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> unregisterMyPushDevice(PushDeviceUnregisterRequest pushDeviceUnregisterRequest) {
        pushNotificationService.unregisterDevice(currentActorService.requireFoodsharingId(), pushDeviceUnregisterRequest.getToken());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<BookingUserResponse> getMyProfile() {
        User bookingUser = userService.getProfileByFoodsharingId(currentActorService.requireFoodsharingId());
        return ResponseEntity.ok(mapper.toBookingUserResponse(bookingUser));
    }

    @Override
    public ResponseEntity<BookingUserResponse> updateMyProfile(UpdateMyProfileRequest request) {
        return ResponseEntity.ok(mapper.toBookingUserResponse(
                userService.updatePhoneNumber(currentActorService.requireFoodsharingId(), request.getPhoneNumber())));
    }

    @Override
    public ResponseEntity<Void> cancelMyBooking(String bezirkSlug, UUID slotId) {
        userService.cancelBooking(bezirkSlug, currentActorService.requireFoodsharingId(), slotId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<MyPickupListResponse> getMyPickups(String bezirkSlug) {
        return ResponseEntity.ok(toMyPickupListResponse(myPickupsService.myPickups(bezirkSlug)));
    }

    @Override
    public ResponseEntity<MyPickupListResponse> addMyPickupExtraSlot(String bezirkSlug, AddPickupExtraSlotRequest request) {
        Instant pickupDate = request.getPickupDate() == null ? null : request.getPickupDate().toInstant();
        return ResponseEntity.ok(toMyPickupListResponse(myPickupsService.addExtraSlot(bezirkSlug, request.getStoreId(), pickupDate)));
    }

    private MyPickupListResponse toMyPickupListResponse(List<FoodsharingMyPickupsService.StorePickupsView> stores) {
        MyPickupListResponse response = new MyPickupListResponse();
        response.setStores(stores.stream().map(store -> {
            MyPickupStoreResponse storeResponse = new MyPickupStoreResponse();
            storeResponse.setStoreId(store.storeId());
            storeResponse.setStoreName(store.storeName());
            storeResponse.setRefreshedAt(toOffsetDateTime(store.refreshedAt()));
            storeResponse.setPickups(store.pickups().stream().map(pickup -> {
                MyPickupResponse pickupResponse = new MyPickupResponse();
                pickupResponse.setPickupDate(toOffsetDateTime(pickup.pickupDate()));
                pickupResponse.setTotalSlots(pickup.totalSlots());
                pickupResponse.setOccupiedSlots(pickup.occupiedSlots());
                pickupResponse.setConfirmed(pickup.confirmed());
                pickupResponse.setFirstInSlot(pickup.firstInSlot());
                pickupResponse.setCanAddExtraSlot(pickup.canAddExtraSlot());
                pickupResponse.setExtraSlotAdded(pickup.extraSlotAdded());
                pickupResponse.setExtraSlotAddedBy(pickup.extraSlotAddedBy());
                pickupResponse.setParticipantNames(pickup.participantNames());
                return pickupResponse;
            }).toList());
            return storeResponse;
        }).toList());
        return response;
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
