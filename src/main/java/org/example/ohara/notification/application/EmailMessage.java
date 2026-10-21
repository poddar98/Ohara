package org.example.ohara.notification.application;

public record EmailMessage(String to, String subject, String body) {
}
