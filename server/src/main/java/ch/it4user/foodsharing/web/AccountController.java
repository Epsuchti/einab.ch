package ch.it4user.foodsharing.web;

import ch.it4user.foodsharing.openapi.api.AccountApi;
import ch.it4user.foodsharing.openapi.model.AccountDeletionConfirmRequest;
import ch.it4user.foodsharing.openapi.model.AccountDeletionRequest;
import ch.it4user.foodsharing.service.AccountDeletionService;
import ch.it4user.foodsharing.service.CurrentActorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController implements AccountApi {

    private final AccountDeletionService accountDeletionService;
    private final CurrentActorService currentActorService;
    private final HttpServletRequest httpRequest;

    public AccountController(AccountDeletionService accountDeletionService, CurrentActorService currentActorService,
                             HttpServletRequest httpRequest) {
        this.accountDeletionService = accountDeletionService;
        this.currentActorService = currentActorService;
        this.httpRequest = httpRequest;
    }

    @Override
    public ResponseEntity<Void> deleteMyAccount() {
        accountDeletionService.deleteOwnAccount(currentActorService.requireFoodsharingId());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> requestAccountDeletion(AccountDeletionRequest request) {
        accountDeletionService.requestDeletion(request.getFoodsharingId(), httpRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> confirmAccountDeletion(AccountDeletionConfirmRequest request) {
        accountDeletionService.confirmDeletion(request.getToken());
        return ResponseEntity.noContent().build();
    }
}
