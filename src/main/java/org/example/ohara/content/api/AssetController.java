package org.example.ohara.content.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.PageResponse;
import org.example.ohara.content.api.dto.AssetResponse;
import org.example.ohara.content.api.dto.CompleteUploadRequest;
import org.example.ohara.content.api.dto.PresignPartsRequest;
import org.example.ohara.content.api.dto.PresignedPartResponse;
import org.example.ohara.content.api.dto.StartUploadRequest;
import org.example.ohara.content.api.dto.UploadResponse;
import org.example.ohara.content.application.AssetService;
import org.example.ohara.content.domain.ContentAsset;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class AssetController {

    public record RenameRequest(@NotBlank @Size(max = 200) String title) {
    }

    private final AssetService assets;

    public AssetController(AssetService assets) {
        this.assets = assets;
    }

    /** Creates the asset and an upload session. Send an Idempotency-Key so a retried request returns the same session. */
    @PostMapping("/api/assets")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse start(@AuthenticationPrincipal CurrentActor actor, @Valid @RequestBody StartUploadRequest request,
                                @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return assets.startUpload(actor, request, idempotencyKey);
    }

    @PostMapping("/api/uploads/{uploadId}/parts")
    public List<PresignedPartResponse> presign(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID uploadId,
                                               @Valid @RequestBody PresignPartsRequest request) {
        return assets.presignParts(actor, uploadId, request.partNumbers());
    }

    @GetMapping("/api/uploads/{uploadId}")
    public UploadResponse status(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID uploadId) {
        return assets.uploadStatus(actor, uploadId);
    }

    @PostMapping("/api/uploads/{uploadId}/complete")
    public AssetResponse complete(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID uploadId,
                                  @Valid @RequestBody CompleteUploadRequest request) {
        return assets.complete(actor, uploadId, request);
    }

    @PostMapping("/api/uploads/{uploadId}/abort")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abort(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID uploadId) {
        assets.abort(actor, uploadId);
    }

    /** {@code ownerOrganizationId} omitted = platform assets. */
    @GetMapping("/api/assets")
    public PageResponse<AssetResponse> library(@AuthenticationPrincipal CurrentActor actor,
                                               @RequestParam(required = false) UUID ownerOrganizationId,
                                               @RequestParam(required = false) ContentAsset.Type type,
                                               @RequestParam(required = false) ContentAsset.Status status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        return assets.library(actor, ownerOrganizationId, type, status, page, size);
    }

    @GetMapping("/api/assets/{assetId}")
    public AssetResponse get(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID assetId) {
        return assets.get(actor, assetId);
    }

    @PatchMapping("/api/assets/{assetId}")
    public AssetResponse rename(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID assetId,
                                @Valid @RequestBody RenameRequest request) {
        return assets.rename(actor, assetId, request.title());
    }

    @PostMapping("/api/assets/{assetId}/reprocess")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AssetResponse reprocess(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID assetId) {
        return assets.reprocess(actor, assetId);
    }

    @PostMapping("/api/assets/{assetId}/retire")
    public AssetResponse retire(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID assetId) {
        return assets.retire(actor, assetId);
    }

    @DeleteMapping("/api/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID assetId) {
        assets.delete(actor, assetId);
    }
}
