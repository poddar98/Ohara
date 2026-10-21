package org.example.ohara.platform.jobs.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableConfigurationProperties(JobProperties.class)
class JobPollingConfig {

    /** Polls only on instances that run jobs; API-only nodes and tests that drive the runner turn it off. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "ohara.jobs.runner-enabled", havingValue = "true", matchIfMissing = true)
    static class Polling {

        private final JobRunner runner;

        Polling(JobRunner runner) {
            this.runner = runner;
        }

        @Scheduled(fixedDelayString = "${ohara.jobs.poll-interval:1s}")
        void poll() {
            runner.runOnce();
        }
    }
}
