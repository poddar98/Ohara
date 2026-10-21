package org.example.ohara.notification.application;

/** Outbound email port. The mock adapter is active until a real provider is chosen (Q-06). */
public interface EmailSender {

    void send(EmailMessage message);
}
