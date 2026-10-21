package org.example.ohara.auth.api.dto;

/** Identical for new and already-registered emails, so registration can't be used to discover accounts. */
public record RegistrationResponse(String status, String email) {

    public static RegistrationResponse verificationRequired(String email) {
        return new RegistrationResponse("VERIFICATION_REQUIRED", email);
    }
}
