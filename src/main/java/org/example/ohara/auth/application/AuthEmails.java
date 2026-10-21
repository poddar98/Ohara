package org.example.ohara.auth.application;

import org.example.ohara.notification.application.EmailMessage;
import org.example.ohara.notification.application.EmailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Account emails. Messages are published as events and sent only after the surrounding transaction commits,
 * so a rolled-back registration never emails a link to a token that doesn't exist.
 */
@Component
public class AuthEmails {

    record Outbound(EmailMessage message) {
    }

    private final ApplicationEventPublisher events;
    private final EmailSender emailSender;
    private final String baseUrl;

    public AuthEmails(ApplicationEventPublisher events, EmailSender emailSender,
                      @Value("${ohara.app.base-url:http://localhost:3000}") String baseUrl) {
        this.events = events;
        this.emailSender = emailSender;
        this.baseUrl = baseUrl;
    }

    void verification(String to, String rawToken) {
        publish(to, "Verify your Ohara email",
            "Confirm your email address to finish creating your account:\n" + link("/verify-email", rawToken)
                + "\n\nThe link expires in 24 hours. If you didn't sign up, ignore this email.");
    }

    void accountAlreadyExists(String to) {
        publish(to, "You already have an Ohara account",
            "Someone tried to create an account with this email. If it was you, sign in or reset your password:\n"
                + baseUrl + "/forgot-password\n\nIf it wasn't you, no action is needed.");
    }

    void passwordReset(String to, String rawToken) {
        publish(to, "Reset your Ohara password",
            "Use this link to choose a new password:\n" + link("/reset-password", rawToken)
                + "\n\nThe link expires in 30 minutes. If you didn't ask for this, ignore this email.");
    }

    void passwordChanged(String to) {
        publish(to, "Your Ohara password was changed",
            "Your password was just changed and you were signed out everywhere. "
                + "If this wasn't you, reset your password immediately: " + baseUrl + "/forgot-password");
    }

    private String link(String path, String rawToken) {
        return baseUrl + path + "?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    private void publish(String to, String subject, String body) {
        events.publishEvent(new Outbound(new EmailMessage(to, subject, body)));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void send(Outbound outbound) {
        emailSender.send(outbound.message());
    }
}
