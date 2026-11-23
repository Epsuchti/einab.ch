package ch.it4user.foodsharing.web;

import ch.it4user.foodsharing.domain.entity.InfoNote;
import ch.it4user.foodsharing.domain.enumtype.UserPermission;
import ch.it4user.foodsharing.openapi.api.InfoNoteApi;
import ch.it4user.foodsharing.openapi.model.AdminInfoNoteListResponse;
import ch.it4user.foodsharing.openapi.model.AdminInfoNoteResponse;
import ch.it4user.foodsharing.openapi.model.InfoNoteListResponse;
import ch.it4user.foodsharing.openapi.model.InfoNoteRequest;
import ch.it4user.foodsharing.openapi.model.InfoNoteResponse;
import ch.it4user.foodsharing.openapi.model.InfoNoteSeverity;
import ch.it4user.foodsharing.service.CurrentActorService;
import ch.it4user.foodsharing.service.InfoNoteService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoNoteController implements InfoNoteApi {

    private final InfoNoteService infoNoteService;
    private final CurrentActorService currentActorService;

    public InfoNoteController(InfoNoteService infoNoteService, CurrentActorService currentActorService) {
        this.infoNoteService = infoNoteService;
        this.currentActorService = currentActorService;
    }

    @Override
    public ResponseEntity<InfoNoteListResponse> getActiveInfoNotes(String bezirkSlug) {
        return ResponseEntity.ok(new InfoNoteListResponse().notes(
                infoNoteService.findActive(bezirkSlug).stream().map(InfoNoteController::toResponse).toList()));
    }

    @Override
    public ResponseEntity<AdminInfoNoteListResponse> listAdminInfoNotes() {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(new AdminInfoNoteListResponse().notes(
                infoNoteService.findAllForAdmin().stream().map(InfoNoteController::toAdminResponse).toList()));
    }

    @Override
    public ResponseEntity<AdminInfoNoteResponse> createAdminInfoNote(InfoNoteRequest request) {
        var admin = currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toAdminResponse(infoNoteService.create(toInput(request), admin)));
    }

    @Override
    public ResponseEntity<AdminInfoNoteResponse> updateAdminInfoNote(UUID infoNoteId, InfoNoteRequest request) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        return ResponseEntity.ok(toAdminResponse(infoNoteService.update(infoNoteId, toInput(request))));
    }

    @Override
    public ResponseEntity<Void> deleteAdminInfoNote(UUID infoNoteId) {
        currentActorService.requirePermission(UserPermission.CAN_MANAGE_USERS);
        infoNoteService.delete(infoNoteId);
        return ResponseEntity.noContent().build();
    }

    private static InfoNoteService.InfoNoteInput toInput(InfoNoteRequest request) {
        return new InfoNoteService.InfoNoteInput(
                request.getBezirkSlug(),
                request.getTitle(),
                request.getMessage(),
                request.getSeverity() == null ? null
                        : ch.it4user.foodsharing.domain.enumtype.InfoNoteSeverity.valueOf(request.getSeverity().getValue()),
                toInstant(request.getStartsAt()),
                toInstant(request.getEndsAt()));
    }

    private static InfoNoteResponse toResponse(InfoNote note) {
        return new InfoNoteResponse()
                .id(note.getId())
                .title(note.getTitle())
                .message(note.getMessage())
                .severity(InfoNoteSeverity.fromValue(note.getSeverity().name()))
                .siteWide(note.getBezirk() == null)
                .startsAt(toOffsetDateTime(note.getStartsAt()))
                .endsAt(toOffsetDateTime(note.getEndsAt()));
    }

    private static AdminInfoNoteResponse toAdminResponse(InfoNote note) {
        return new AdminInfoNoteResponse()
                .id(note.getId())
                .title(note.getTitle())
                .message(note.getMessage())
                .severity(InfoNoteSeverity.fromValue(note.getSeverity().name()))
                .bezirkSlug(note.getBezirk() == null ? null : note.getBezirk().getSlug())
                .bezirkName(note.getBezirk() == null ? null : note.getBezirk().getName())
                .startsAt(toOffsetDateTime(note.getStartsAt()))
                .endsAt(toOffsetDateTime(note.getEndsAt()))
                .createdByName(note.getCreatedByName())
                .createdAt(toOffsetDateTime(note.getCreatedAt()));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
