package org.example.ohara.content.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.example.ohara.content.domain.ContentAsset;

import java.util.UUID;

/** {@code ownerOrganizationId} null = platform asset. {@code sha256} (hex) is verified on completion when given. */
public record StartUploadRequest(
    @NotNull ContentAsset.Type type,
    @NotBlank @Size(max = 200) String title,
    UUID ownerOrganizationId,
    @NotBlank @Size(max = 255) String fileName,
    @NotNull @Positive Long bytes,
    @NotBlank @Size(max = 128) String mimeType,
    @Pattern(regexp = "^[0-9a-f]{64}$", message = "sha256 must be 64 lowercase hex characters") String sha256,
    ContentAsset.ContentProfile contentProfile
) {
}
