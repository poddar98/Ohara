package org.example.ohara.user.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Account lifecycle events. Listeners run synchronously in the same transaction, so an account is never left half
 * anonymized: either every module scrubbed its personal data, or nothing changed.
 */
@Component
public class AccountEvents {

    /** The account is being anonymized; remove or scrub personal data keyed by the id or the former email. */
    public record Anonymizing(UUID userId, String formerEmail) {
    }

    private final ApplicationEventPublisher publisher;

    AccountEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    void anonymizing(UUID userId, String formerEmail) {
        publisher.publishEvent(new Anonymizing(userId, formerEmail));
    }
}
