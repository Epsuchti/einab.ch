package ch.it4user.foodsharing.web;

import ch.it4user.foodsharing.domain.entity.SupportRequest;
import ch.it4user.foodsharing.openapi.api.SupportApi;
import ch.it4user.foodsharing.openapi.model.SupportClientPlatform;
import ch.it4user.foodsharing.openapi.model.SupportRequestCategory;
import ch.it4user.foodsharing.openapi.model.SupportRequestCreateRequest;
import ch.it4user.foodsharing.openapi.model.SupportRequestListResponse;
import ch.it4user.foodsharing.openapi.model.SupportRequestResponse;
import ch.it4user.foodsharing.openapi.model.SupportRequestStatus;
import ch.it4user.foodsharing.openapi.model.SupportRequestStatusUpdateRequest;
import ch.it4user.foodsharing.service.SupportRequestService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupportController implements SupportApi {

    private final SupportRequestService supportRequestService;
    private final HttpServletRequest httpRequest;

    public SupportController(SupportRequestService supportRequestService, HttpServletRequest httpRequest) {
        this.supportRequestService = supportRequestService;
        this.httpRequest = httpRequest;
    }

    @Override
    public ResponseEntity<SupportRequestResponse> createSupportRequest(SupportRequestCreateRequest request) {
        SupportRequest created = supportRequestService.create(new SupportRequestService.NewSupportRequest(
                request.getCategory() == null ? null
                        : ch.it4user.foodsharing.domain.enumtype.SupportRequestCategory.valueOf(request.getCategory().getValue()),
                request.getMessage(),
                request.getBezirkSlug(),
                request.getContactFoodsharingId(),
                request.getContactName(),
                request.getClientVersion(),
                request.getPlatform() == null ? null
                        : ch.it4user.foodsharing.domain.enumtype.SupportClientPlatform.valueOf(request.getPlatform().getValue()),
                request.getPageUrl(),
                request.getUserAgent()), httpRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @Override
    public ResponseEntity<SupportRequestListResponse> listSupportRequests(SupportRequestStatus status, Integer page, Integer size) {
        Page<SupportRequest> result = supportRequestService.list(
                status == null ? null : ch.it4user.foodsharing.domain.enumtype.SupportRequestStatus.valueOf(status.getValue()),
                page == null ? 0 : page, size == null ? 20 : size);
        SupportRequestListResponse response = new SupportRequestListResponse();
        response.setRequests(result.getContent().stream().map(SupportController::toResponse).toList());
        response.setPage(result.getNumber());
        response.setSize(result.getSize());
        response.setTotalElements(result.getTotalElements());
        response.setTotalPages(result.getTotalPages());
        response.setOpenCount(supportRequestService.openCount());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<SupportRequestResponse> updateSupportRequestStatus(UUID supportRequestId,
            SupportRequestStatusUpdateRequest request) {
        return ResponseEntity.ok(toResponse(supportRequestService.updateStatus(supportRequestId,
                ch.it4user.foodsharing.domain.enumtype.SupportRequestStatus.valueOf(request.getStatus().getValue()))));
    }

    private static SupportRequestResponse toResponse(SupportRequest request) {
        SupportRequestResponse response = new SupportRequestResponse();
        response.setId(request.getId());
        response.setCategory(SupportRequestCategory.fromValue(request.getCategory().name()));
        response.setMessage(request.getMessage());
        response.setStatus(SupportRequestStatus.fromValue(request.getStatus().name()));
        response.setCreatedAt(toOffsetDateTime(request.getCreatedAt()));
        response.setDoneAt(toOffsetDateTime(request.getDoneAt()));
        response.setDoneByName(request.getDoneByName());
        response.setFoodsharingId(request.getContactFoodsharingId());
        response.setSignedIn(request.getUser() != null);
        response.setUserName(request.getContactName());
        response.setPhoneNumber(request.getUser() == null ? null : request.getUser().getPhoneNumber());
        response.setBezirkName(request.getBezirk() == null ? null : request.getBezirk().getName());
        response.setClientVersion(request.getClientVersion());
        response.setServerVersion(request.getServerVersion());
        response.setPlatform(request.getPlatform() == null ? null : SupportClientPlatform.fromValue(request.getPlatform().name()));
        response.setPageUrl(request.getPageUrl());
        response.setUserAgent(request.getUserAgent());
        return response;
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
