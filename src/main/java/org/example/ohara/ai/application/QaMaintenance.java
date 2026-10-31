package org.example.ohara.ai.application;

import org.example.ohara.ai.infrastructure.persistence.AiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Deletes Q&A log rows past their retention (docs 03 §9.4: 90 days). */
@Component
@ConditionalOnProperty(name = "ohara.jobs.runner-enabled", havingValue = "true", matchIfMissing = true)
class QaMaintenance {

    private static final Logger log = LoggerFactory.getLogger(QaMaintenance.class);

    private final AiStore store;
    private final Duration retention;

    QaMaintenance(AiStore store, @Value("${ohara.ai.qa.log-retention:90d}") Duration retention) {
        this.store = store;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${ohara.ai.qa.purge-interval:6h}", initialDelayString = "5m")
    void purge() {
        int deleted = store.purgeQuestionsBefore(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("Purged {} Q&A log row(s)", deleted);
        }
    }
}
