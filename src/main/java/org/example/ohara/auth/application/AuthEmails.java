package org.example.ohara.auth.application;

import org.example.ohara.notification.application.EmailMessage;
import org.example.ohara.notification.application.EmailOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Account emails, queued in the caller's transaction (see {@link EmailOutbox}): a rolled-back registration never
 * emails a link to a token that doesn't exist, and a provider outage only delays delivery.
 */
@Component
public class AuthEmails {

    private final EmailOutbox outbox;
    private final String baseUrl;

    public AuthEmails(EmailOutbox outbox, @Value("${ohara.app.base-url:http://localhost:3000}") String baseUrl) {
        this.outbox = outbox;
        this.baseUrl = baseUrl;
    }

    void verification(String to, String rawToken) {
        send(to, "verification:" + SecretTokens.sha256(rawToken), "Verify your Ohara email",
            "Confirm your email address to finish creating your account:\n" + link("/verify-email", rawToken)
                + "\n\nThe link expires in 24 hours. If you didn't sign up, ignore this email.");
    }

    void accountAlreadyExists(String to) {
        send(to, null, "You already have an Ohara account",
            "Someone tried to create an account with this email. If it was you, sign in or reset your password:\n"
                + baseUrl + "/forgot-password\n\nIf it wasn't you, no action is needed.");
    }

    void passwordReset(String to, String rawToken) {
        send(to, "password-reset:" + SecretTokens.sha256(rawToken), "Reset your Ohara password",
            "Use this link to choose a new password:\n" + link("/reset-password", rawToken)
                + "\n\nThe link expires in 30 minutes. If you didn't ask for this, ignore this email.");
    }

    void setPassword(String to, String rawToken) {
        send(to, "set-password:" + SecretTokens.sha256(rawToken), "Your Ohara account is ready",
            "An account was created for you on Ohara. Choose a password to sign in:\n" + link("/reset-password", rawToken)
                + "\n\nThe link expires in 72 hours. You can request a new one from the sign-in page.");
    }

    void passwordChanged(String to) {
        send(to, null, "Your Ohara password was changed",
            "Your password was just changed and you were signed out everywhere. "
                + "If this wasn't you, reset your password immediately: " + baseUrl + "/forgot-password");
    }

    void deletionScheduled(String to, Instant dueAt) {
        send(to, null, "Your Ohara account will be deleted",
            "You asked us to delete your Ohara account. You've been signed out and the account is closed. Your personal "
                + "details will be removed permanently on " + DateTimeFormatter.ISO_LOCAL_DATE.format(dueAt.atOffset(ZoneOffset.UTC))
                + " (UTC). Until then, contact support to cancel.\n\nRecords we must keep (payment references, course "
                + "completion and attendance) stay, without your name or email.");
    }

    void deletionDeferred(String to) {
        send(to, null, "Your Ohara account deletion is on hold",
            "We can't delete your Ohara account yet because a payment or refund matter is still open. We'll complete "
                + "the deletion once it's resolved; contact support if you have questions.");
    }

    private String link(String path, String rawToken) {
        return baseUrl + path + "?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    /** Token emails dedupe on the token; notices are always new emails. */
    private void send(String to, String dedupeKey, String subject, String body) {
        EmailMessage message = new EmailMessage(to, subject, body);
        if (dedupeKey == null) {
            outbox.enqueue(message);
        } else {
            outbox.enqueue(message, dedupeKey);
        }
    }
}
