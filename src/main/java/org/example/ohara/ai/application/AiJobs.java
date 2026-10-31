package org.example.ohara.ai.application;

import org.example.ohara.content.application.VideoEvents;
import org.example.ohara.platform.jobs.JobHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/** Registers the AI steps with the job runner, and starts transcription whenever a video becomes READY. */
@Configuration
class AiJobs {

    private final TranscriptionPipeline pipeline;
    private final boolean autoTranscribe;

    AiJobs(TranscriptionPipeline pipeline, @Value("${ohara.ai.auto-transcribe:true}") boolean autoTranscribe) {
        this.pipeline = pipeline;
        this.autoTranscribe = autoTranscribe;
    }

    /** Runs in the asset's READY transaction, so the job exists exactly when the video does. */
    @EventListener
    void on(VideoEvents.Ready event) {
        if (autoTranscribe) {
            pipeline.request(event.assetId(), null, false);
        }
    }

    @Bean
    JobHandler<TranscriptionPipeline.TranscribeWork> aiTranscribeHandler() {
        return new JobHandler<>() {
            public String jobType() { return TranscriptionPipeline.TRANSCRIBE_JOB; }
            public Class<TranscriptionPipeline.TranscribeWork> payloadType() { return TranscriptionPipeline.TranscribeWork.class; }
            public void handle(TranscriptionPipeline.TranscribeWork work) { pipeline.transcribe(work); }
        };
    }

    @Bean
    JobHandler<TranscriptionPipeline.CheckWork> aiTranscribeCheckHandler() {
        return new JobHandler<>() {
            public String jobType() { return TranscriptionPipeline.CHECK_JOB; }
            public Class<TranscriptionPipeline.CheckWork> payloadType() { return TranscriptionPipeline.CheckWork.class; }
            public void handle(TranscriptionPipeline.CheckWork work) { pipeline.check(work); }
        };
    }
}
