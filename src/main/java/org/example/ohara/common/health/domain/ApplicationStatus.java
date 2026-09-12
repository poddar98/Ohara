package org.example.ohara.common.health.domain;

public record ApplicationStatus(String status, String service, String version) {
}
