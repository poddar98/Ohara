package org.example.ohara.content.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic clean-up on worker instances: abandoned uploads are expired and their stored parts discarded. */
@Component
@ConditionalOnProperty(name = "ohara.jobs.runner-enabled", havingValue = "true", matchIfMissing = true)
class AssetMaintenance {

    private static final Logger log = LoggerFactory.getLogger(AssetMaintenance.class);

    private final AssetService assets;

    AssetMaintenance(AssetService assets) {
        this.assets = assets;
    }

    @Scheduled(fixedDelayString = "${ohara.content.upload-sweep-interval:10m}", initialDelayString = "1m")
    void expireAbandonedUploads() {
        int expired = assets.expireAbandonedUploads();
        if (expired > 0) {
            log.info("Expired {} abandoned upload(s)", expired);
        }
    }
}
