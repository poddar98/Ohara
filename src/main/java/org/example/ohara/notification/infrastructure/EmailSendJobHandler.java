package org.example.ohara.notification.infrastructure;

import org.example.ohara.notification.application.EmailMessage;
import org.example.ohara.notification.application.EmailOutbox;
import org.example.ohara.notification.application.EmailSender;
import org.example.ohara.platform.jobs.JobHandler;
import org.springframework.stereotype.Component;

/** Delivers queued emails. At-least-once: a crash right after the provider accepts a message can resend it. */
@Component
class EmailSendJobHandler implements JobHandler<EmailMessage> {

    private final EmailSender emailSender;

    EmailSendJobHandler(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @Override
    public String jobType() {
        return EmailOutbox.JOB_TYPE;
    }

    @Override
    public Class<EmailMessage> payloadType() {
        return EmailMessage.class;
    }

    @Override
    public void handle(EmailMessage message) {
        emailSender.send(message);
    }
}
