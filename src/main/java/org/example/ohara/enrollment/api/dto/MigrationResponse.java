package org.example.ohara.enrollment.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record MigrationResponse(UUID enrollmentId, UUID fromVersionId, UUID toVersionId, BigDecimal progressPercent) {
}
