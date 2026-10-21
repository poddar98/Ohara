package org.example.ohara.organization.api;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.common.web.PageResponse;
import org.example.ohara.organization.api.dto.ImportResponse;
import org.example.ohara.organization.api.dto.ImportRowResponse;
import org.example.ohara.organization.application.BulkImportService;
import org.example.ohara.organization.domain.BulkImportJob;
import org.example.ohara.organization.domain.BulkImportRow;
import org.example.ohara.organization.domain.OrgRole;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orgs/{orgId}/imports")
public class BulkImportController {

    private final BulkImportService imports;

    public BulkImportController(BulkImportService imports) {
        this.imports = imports;
    }

    /** Multipart upload of a CSV file. Validates every row and applies nothing until confirmed. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImportResponse upload(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                 @RequestPart("file") MultipartFile file,
                                 @RequestParam(defaultValue = "INVITE") BulkImportJob.Mode mode,
                                 @RequestParam(required = false) OrgRole role,
                                 @RequestParam(required = false) UUID cohortId) throws IOException {
        if (file.getSize() > 1024 * 1024) {
            throw new ApiException.BadRequest("file_too_large", "Files are limited to 1 MB");
        }
        return imports.upload(actor, orgId, file.getOriginalFilename(), file.getBytes(), mode, role, cohortId);
    }

    @GetMapping
    public List<ImportResponse> list(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId) {
        return imports.list(actor, orgId);
    }

    @GetMapping("/{importId}")
    public ImportResponse get(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId, @PathVariable UUID importId) {
        return imports.get(actor, orgId, importId);
    }

    @GetMapping("/{importId}/rows")
    public PageResponse<ImportRowResponse> rows(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                                @PathVariable UUID importId,
                                                @RequestParam(required = false) BulkImportRow.Status status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "100") int size) {
        return imports.rows(actor, orgId, importId, status, page, size);
    }

    @GetMapping(value = "/{importId}/errors.csv", produces = "text/csv")
    public ResponseEntity<String> errorReport(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId,
                                              @PathVariable UUID importId) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-" + importId + "-errors.csv\"")
            .contentType(new MediaType("text", "csv"))
            .body(imports.errorReport(actor, orgId, importId));
    }

    @PostMapping("/{importId}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportResponse confirm(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId, @PathVariable UUID importId) {
        return imports.confirm(actor, orgId, importId);
    }

    @PostMapping("/{importId}/cancel")
    public ImportResponse cancel(@AuthenticationPrincipal CurrentActor actor, @PathVariable UUID orgId, @PathVariable UUID importId) {
        return imports.cancel(actor, orgId, importId);
    }
}
