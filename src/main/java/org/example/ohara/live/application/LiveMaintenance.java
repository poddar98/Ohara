package org.example.ohara.live.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Periodically ends live sessions whose stream never came back or that ran far past their slot. */
@Component
@ConditionalOnProperty(name = "ohara.jobs.runner-enabled", havingValue = "true", matchIfMissing = true)
class LiveMaintenance {

    private static final Logger log = LoggerFactory.getLogger(LiveMaintenance.class);

    private final LiveStreamEvents events;

    LiveMaintenance(LiveStreamEvents events) {
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${ohara.live.sweep-interval:1m}", initialDelayString = "1m")
    void endAbandonedSessions() {
        int ended = events.sweep(Instant.now());
        if (ended > 0) {
            log.info("Ended {} abandoned live session(s)", ended);
        }
    }
}
