package org.example.ohara.live.infrastructure.ivs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;

/**
 * Long-polls the SQS queue that EventBridge feeds with IVS events (docs 09: provider events arrive via
 * EventBridge → SQS, not webhooks). A message is deleted only after it was handled; one that fails becomes visible
 * again after the queue's visibility timeout and, after the redrive policy's max receives, lands in the DLQ.
 * Runs on its own virtual thread so long polls never block the job scheduler.
 */
class SqsEventPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SqsEventPoller.class);

    private final SqsClient sqs;
    private final String queueUrl;
    private final IvsEventIntake intake;
    private final int waitSeconds;
    private volatile boolean running;
    private Thread thread;

    SqsEventPoller(SqsClient sqs, String queueUrl, IvsEventIntake intake, int waitSeconds) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.intake = intake;
        this.waitSeconds = waitSeconds;
    }

    /** One receive; returns how many messages were handled and deleted. */
    int pollOnce() {
        List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(waitSeconds))
            .messages();
        int done = 0;
        for (Message message : messages) {
            try {
                intake.handle(message.body());
                sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                done++;
            } catch (RuntimeException e) {
                log.warn("IVS event {} failed; it will be retried: {}", message.messageId(), e.toString());
            }
        }
        return done;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofVirtual().name("ivs-events").start(() -> {
            while (running) {
                try {
                    pollOnce();
                } catch (RuntimeException e) {
                    log.error("Polling IVS events failed; retrying in 5 s", e);
                    sleep();
                }
            }
        });
    }

    private static void sleep() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
