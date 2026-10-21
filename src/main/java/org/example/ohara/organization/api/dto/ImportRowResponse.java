package org.example.ohara.organization.api.dto;

import org.example.ohara.organization.domain.BulkImportRow;

public record ImportRowResponse(int rowNumber, String email, String firstName, String lastName, String externalRef,
                                BulkImportRow.Status status, String errorCode, String errorDetail) {

    public static ImportRowResponse of(BulkImportRow row) {
        return new ImportRowResponse(row.getRowNumber(), row.getEmail(), row.getFirstName(), row.getLastName(),
            row.getExternalRef(), row.getStatus(), row.getErrorCode(), row.getErrorDetail());
    }
}
