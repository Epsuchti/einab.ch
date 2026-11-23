package ch.it4user.foodsharing.web;

import ch.it4user.foodsharing.openapi.api.AdminImportApi;
import ch.it4user.foodsharing.openapi.model.PastEinAbImportPreview;
import ch.it4user.foodsharing.openapi.model.PastEinAbImportRequest;
import ch.it4user.foodsharing.openapi.model.PastEinAbImportResult;
import ch.it4user.foodsharing.openapi.model.UserRefreshProgress;
import ch.it4user.foodsharing.service.BezirkService;
import ch.it4user.foodsharing.service.CurrentActorService;
import ch.it4user.foodsharing.service.PastEinAbImportService;
import ch.it4user.foodsharing.service.UserRefreshQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminImportController implements AdminImportApi {

    private final CurrentActorService currentActorService;
    private final PastEinAbImportService importService;
    private final UserRefreshQueueService refreshQueueService;
    private final BezirkService bezirkService;

    public AdminImportController(CurrentActorService currentActorService,
                                 PastEinAbImportService importService,
                                 UserRefreshQueueService refreshQueueService,
                                 BezirkService bezirkService) {
        this.currentActorService = currentActorService;
        this.importService = importService;
        this.refreshQueueService = refreshQueueService;
        this.bezirkService = bezirkService;
    }

    @Override
    public ResponseEntity<PastEinAbImportPreview> previewPastEinAbImport(
            String bezirkSlug, PastEinAbImportRequest request) {
        return ResponseEntity.ok(importService.preview(bezirkSlug, currentActorService.requireAdmin(), request));
    }

    @Override
    public ResponseEntity<PastEinAbImportResult> confirmPastEinAbImport(
            String bezirkSlug, PastEinAbImportRequest request) {
        return ResponseEntity.ok(importService.confirm(bezirkSlug, currentActorService.requireAdmin(), request));
    }

    @Override
    public ResponseEntity<UserRefreshProgress> getUserRefreshProgress(String bezirkSlug) {
        currentActorService.requireAdmin();
        return ResponseEntity.ok(refreshQueueService.progress(bezirkService.requireActive(bezirkSlug)));
    }
}
