package ch.it4user.foodsharing.web;

import ch.it4user.foodsharing.openapi.api.AuthApi;
import ch.it4user.foodsharing.openapi.model.AuthResponse;
import ch.it4user.foodsharing.openapi.model.DemoAccountRequest;
import ch.it4user.foodsharing.openapi.model.LoginRequest;
import ch.it4user.foodsharing.openapi.model.MessageResponse;
import ch.it4user.foodsharing.openapi.model.VerifyTokenRequest;
import ch.it4user.foodsharing.openapi.model.PrivacyConsentRequest;
import ch.it4user.foodsharing.openapi.model.SessionInfoResponse;
import ch.it4user.foodsharing.service.ApiErrorCode;
import ch.it4user.foodsharing.service.ApiException;
import ch.it4user.foodsharing.service.AuthService;
import ch.it4user.foodsharing.service.CurrentActorService;
import ch.it4user.foodsharing.service.DemoAccounts;
import ch.it4user.foodsharing.service.DemoLoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController implements AuthApi {

    private final AuthService authService;
    private final CurrentActorService currentActorService;
    private final HttpServletRequest request;
    private final DemoLoginService demoLoginService;

    public AuthController(AuthService authService, CurrentActorService currentActorService, HttpServletRequest request,
                          DemoLoginService demoLoginService) {
        this.authService = authService;
        this.demoLoginService = demoLoginService;
        this.currentActorService = currentActorService;
        this.request = request;
    }

    @Override
    public ResponseEntity<MessageResponse> requestLogin(String bezirkSlug, LoginRequest loginRequest) {
        // The shared tester ID gets a new demo account through the app, never a login link to a real Foodsharing user.
        if (demoLoginService.isSharedFoodsharingId(loginRequest.getFoodsharingId())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT);
        }
        return ResponseEntity.ok(authService.requestLogin(
                bezirkSlug, loginRequest.getFoodsharingId(), loginRequest.getReturnUrl(), request.getRemoteAddr()));
    }

    @Override
    public ResponseEntity<AuthResponse> createDemoAccount(DemoAccountRequest demoAccountRequest) {
        DemoAccounts.Role role = demoAccountRequest.getRole() == DemoAccountRequest.RoleEnum.TEACHER
                ? DemoAccounts.Role.TEACHER : DemoAccounts.Role.NEWCOMER;
        return ResponseEntity.ok(demoLoginService.createAccount(demoAccountRequest.getFoodsharingId(), role, request.getRemoteAddr()));
    }

    @Override
    public ResponseEntity<AuthResponse> verifyLoginToken(VerifyTokenRequest verifyTokenRequest) {
        return ResponseEntity.ok(authService.verifyToken(verifyTokenRequest.getToken()));
    }

    @Override
    public ResponseEntity<AuthResponse> acceptPrivacyConsent(PrivacyConsentRequest request) {
        return ResponseEntity.ok(authService.acceptPrivacyConsent(
                currentActorService.requireFoodsharingId(), request.getVersion()));
    }

    @Override
    public ResponseEntity<SessionInfoResponse> acknowledgeBezirkInfo() {
        return ResponseEntity.ok(authService.acknowledgeBezirkInfo(currentActorService.requireFoodsharingId()));
    }

    @Override
    public ResponseEntity<SessionInfoResponse> getCurrentSession() {
        return ResponseEntity.ok(authService.currentSession(currentActorService.requireFoodsharingId()));
    }
}
