package org.example.ohara.notification.infrastructure;

import org.example.ohara.notification.application.EmailMessage;
import org.example.ohara.notification.application.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Mock adapter: logs the email and keeps the most recent ones in memory so local developers and tests can read
 * the links. Never active in production because a real provider sets {@code ohara.email.provider}.
 */
@Component
@ConditionalOnProperty(name = "ohara.email.provider", havingValue = "mock", matchIfMissing = true)
public class InMemoryEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEmailSender.class);
    private static final int CAPACITY = 200;

    private final Deque<EmailMessage> sent = new ArrayDeque<>();

    @Override
    public synchronized void send(EmailMessage message) {
        log.info("[mock email] to={} subject=\"{}\"\n{}", message.to(), message.subject(), message.body());
        if (sent.size() == CAPACITY) {
            sent.removeFirst();
        }
        sent.addLast(message);
    }

    public synchronized List<EmailMessage> sentTo(String email) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(email)).toList();
    }

    public synchronized Optional<EmailMessage> lastSentTo(String email) {
        List<EmailMessage> messages = sentTo(email);
        return messages.isEmpty() ? Optional.empty() : Optional.of(messages.getLast());
    }
}
