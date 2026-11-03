package org.example.ohara.ai.infrastructure.aws;

import org.example.ohara.ai.application.SpeechToText;
import org.example.ohara.content.application.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.mediaconvert.MediaConvertClient;
import software.amazon.awssdk.services.transcribe.TranscribeClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Amazon Transcribe when {@code ohara.ai.stt.provider=transcribe}. Requires S3 storage: the audio extract and the
 * transcript are written to the source bucket. The audio extraction runs on MediaConvert with the same IAM role as
 * transcoding ({@code ohara.media.mediaconvert.role-arn}); {@code ohara.ai.stt.transcribe.queue-arn} can put it on its
 * own queue so transcription never waits behind video jobs. The clients are owned by the adapter (not beans), so they
 * don't clash with the transcoder's MediaConvert client.
 */
@Configuration
@ConditionalOnProperty(name = "ohara.ai.stt.provider", havingValue = "transcribe")
class AwsSpeechConfig {

    @Bean(destroyMethod = "close")
    SpeechToText speechToText(ObjectStorage storage, JsonMapper json,
                              @Value("${ohara.aws.region:ap-south-1}") String region,
                              @Value("${ohara.storage.s3.source-bucket}") String sourceBucket,
                              @Value("${ohara.storage.s3.delivery-bucket}") String deliveryBucket,
                              @Value("${ohara.media.mediaconvert.role-arn}") String roleArn,
                              @Value("${ohara.ai.stt.transcribe.queue-arn:${ohara.media.mediaconvert.queue-arn:}}") String queueArn,
                              @Value("${ohara.ai.stt.transcribe.multiple-languages:false}") boolean multipleLanguages,
                              @Value("${ohara.ai.stt.transcribe.max-speakers:4}") int maxSpeakers) {
        Region awsRegion = Region.of(region);
        TranscribeClient transcribe = TranscribeClient.builder().region(awsRegion)
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        MediaConvertClient mediaConvert = MediaConvertClient.builder().region(awsRegion)
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        return new TranscribeSpeechToText(transcribe, mediaConvert, storage, json, sourceBucket, deliveryBucket, roleArn,
            queueArn, multipleLanguages, maxSpeakers);
    }
}
