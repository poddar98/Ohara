package org.example.ohara.content.application;

import org.example.ohara.platform.jobs.JobHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the pipeline steps with the job runner. */
@Configuration
class MediaJobHandlers {

    @Bean
    JobHandler<AssetService.ProcessWork> mediaProcessHandler(MediaPipeline pipeline) {
        return new JobHandler<>() {
            public String jobType() { return AssetService.PROCESS_JOB; }
            public Class<AssetService.ProcessWork> payloadType() { return AssetService.ProcessWork.class; }
            public void handle(AssetService.ProcessWork work) { pipeline.process(work.assetId()); }
        };
    }

    @Bean
    JobHandler<MediaPipeline.CheckWork> mediaCheckHandler(MediaPipeline pipeline) {
        return new JobHandler<>() {
            public String jobType() { return MediaPipeline.CHECK_JOB; }
            public Class<MediaPipeline.CheckWork> payloadType() { return MediaPipeline.CheckWork.class; }
            public void handle(MediaPipeline.CheckWork work) { pipeline.check(work); }
        };
    }
}
