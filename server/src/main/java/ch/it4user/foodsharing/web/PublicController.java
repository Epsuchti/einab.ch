package ch.it4user.foodsharing.web;

import ch.it4user.foodsharing.domain.entity.User;
import ch.it4user.foodsharing.openapi.api.PublicApi;
import ch.it4user.foodsharing.openapi.model.AvailableSlotListResponse;
import ch.it4user.foodsharing.openapi.model.BookSlotRequest;
import ch.it4user.foodsharing.openapi.model.BookingDetailResponse;
import ch.it4user.foodsharing.openapi.model.AuthResponse;
import ch.it4user.foodsharing.openapi.model.BezirkListResponse;
import ch.it4user.foodsharing.openapi.model.EinAbCategory;
import ch.it4user.foodsharing.openapi.model.DemoLoginStatusResponse;
import ch.it4user.foodsharing.openapi.model.FoodsharingStatusResponse;
import ch.it4user.foodsharing.openapi.model.Language;
import ch.it4user.foodsharing.openapi.model.NotificationSubscriptionResponse;
import ch.it4user.foodsharing.openapi.model.NotificationUnsubscribeRequest;
import ch.it4user.foodsharing.service.AuthService;
import ch.it4user.foodsharing.service.BezirkService;
import ch.it4user.foodsharing.service.CurrentActorService;
import ch.it4user.foodsharing.service.DemoLoginService;
import ch.it4user.foodsharing.service.FoodsharingAvailability;
import ch.it4user.foodsharing.service.PublicService;
import ch.it4user.foodsharing.service.NotificationService;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PublicController implements PublicApi {

    private final PublicService publicService;
    private final NotificationService notificationService;
    private final AuthService authService;
    private final BezirkService bezirkService;
    private final CurrentActorService currentActorService;
    private final ApiModelMapper mapper;
    private final FoodsharingAvailability foodsharingAvailability;
    private final DemoLoginService demoLoginService;

    public PublicController(PublicService publicService,
                            NotificationService notificationService,
                            AuthService authService,
                            BezirkService bezirkService,
                            CurrentActorService currentActorService,
                            ApiModelMapper mapper,
                            FoodsharingAvailability foodsharingAvailability,
                            DemoLoginService demoLoginService) {
        this.demoLoginService = demoLoginService;
        this.foodsharingAvailability = foodsharingAvailability;
        this.publicService = publicService;
        this.notificationService = notificationService;
        this.authService = authService;
        this.bezirkService = bezirkService;
        this.currentActorService = currentActorService;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<FoodsharingStatusResponse> getFoodsharingAvailability() {
        java.time.Instant offlineSince = foodsharingAvailability.offlineSince();
        return ResponseEntity.ok(new FoodsharingStatusResponse()
                .online(offlineSince == null)
                .offlineSince(offlineSince == null ? null : offlineSince.atOffset(java.time.ZoneOffset.UTC)));
    }

    @Override
    public ResponseEntity<DemoLoginStatusResponse> getDemoLoginStatus() {
        return ResponseEntity.ok(new DemoLoginStatusResponse()
                .enabled(demoLoginService.isEnabled())
                .sharedFoodsharingId(demoLoginService.sharedFoodsharingId()));
    }

    @Override
    public ResponseEntity<BezirkListResponse> getBezirke() {
        return ResponseEntity.ok(mapper.toBezirkListResponse(bezirkService.findAllActiveWithDisabledCategories()));
    }

    @Override
    public ResponseEntity<AvailableSlotListResponse> getAvailableSlots(
            String bezirkSlug,
            String search,
            EinAbCategory category,
            Boolean visitFairteiler,
            Integer page,
            Integer size) {
        User currentUser = currentActorService.findCurrentUser().orElse(null);
        boolean includeTeacherDetails = currentUser != null && currentUser.isCanGiveEinAbs();
        return ResponseEntity.ok(mapper.toAvailableSlotListResponse(
                publicService.findAvailableSlots(bezirkSlug, search, mapCategory(category), visitFairteiler,
                        page == null ? 0 : page, size == null ? 20 : size,
                        currentUser),
                includeTeacherDetails));
    }

    @Override
    public ResponseEntity<NotificationSubscriptionResponse> unsubscribeNotifications(NotificationUnsubscribeRequest notificationUnsubscribeRequest) {
        return ResponseEntity.ok(mapper.toNotificationSubscriptionResponse(
                notificationService.unsubscribe(notificationUnsubscribeRequest.getToken())));
    }

    private ch.it4user.foodsharing.domain.enumtype.EinAbCategory mapCategory(EinAbCategory category) {
        return category == null ? null : ch.it4user.foodsharing.domain.enumtype.EinAbCategory.valueOf(category.getValue());
    }

    private ch.it4user.foodsharing.domain.enumtype.LanguageCode mapLanguage(Language language) {
        return ch.it4user.foodsharing.domain.enumtype.LanguageCode.fromCode(language == null ? "de" : language.getValue());
    }
}
