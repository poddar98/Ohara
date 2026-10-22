package org.example.ohara.user.api.dto;

/** Null fields are unchanged. {@code acceptTerms} may only be true (accepting the current version). */
public record ConsentUpdateRequest(Boolean acceptTerms, Boolean marketingEmails) {
}
