package org.example.ohara.organization.application;

import org.example.ohara.notification.application.EmailMessage;
import org.example.ohara.notification.application.EmailOutbox;
import org.example.ohara.organization.domain.OrgRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;

@Component
class InvitationEmails {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC);

    private final EmailOutbox outbox;
    private final String baseUrl;

    InvitationEmails(EmailOutbox outbox, @Value("${ohara.app.base-url:http://localhost:3000}") String baseUrl) {
        this.outbox = outbox;
        this.baseUrl = baseUrl;
    }

    /** Deduped on the token hash: a retried send of the same invitation link is one email. */
    void invitation(String to, String organizationName, OrgRole role, String rawToken, String tokenHash, Instant expiresAt) {
        String link = baseUrl + "/invitations/accept?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        outbox.enqueue(new EmailMessage(to, "You're invited to join " + organizationName + " on Ohara",
            organizationName + " has invited you to join as " + describe(role) + ".\n\nAccept the invitation:\n" + link
                + "\n\nIf you already have an Ohara account, sign in with this email address first. "
                + "The invitation expires on " + DATE.format(expiresAt) + "."),
            "invitation:" + tokenHash);
    }

    private static String describe(OrgRole role) {
        return switch (role) {
            case ORG_ADMIN -> "an administrator";
            case INSTRUCTOR -> "an instructor";
            case LEARNER -> "a learner";
        };
    }
}
