package org.example.ohara.content.infrastructure.aws;

import org.example.ohara.content.application.Transcoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.mediaconvert.MediaConvertClient;

/**
 * MediaConvert transcoding when {@code ohara.media.transcoder=mediaconvert}. MediaConvert reads sources from the
 * source bucket and writes to the delivery bucket through {@code role-arn} (an IAM role it assumes); {@code queue-arn}
 * is optional (the account's default queue otherwise).
 */
@Configuration
@ConditionalOnProperty(name = "ohara.media.transcoder", havingValue = "mediaconvert")
class AwsMediaConfig {

    @Bean(destroyMethod = "close")
    MediaConvertClient mediaConvertClient(@Value("${ohara.aws.region:ap-south-1}") String region) {
        return MediaConvertClient.builder().region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    }

    @Bean
    Transcoder transcoder(MediaConvertClient mediaConvert,
                          @Value("${ohara.storage.s3.source-bucket}") String sourceBucket,
                          @Value("${ohara.storage.s3.delivery-bucket}") String deliveryBucket,
                          @Value("${ohara.media.mediaconvert.role-arn}") String roleArn,
                          @Value("${ohara.media.mediaconvert.queue-arn:}") String queueArn,
                          @Value("${ohara.media.mediaconvert.qvbr-quality:7}") int qvbrQuality) {
        return new MediaConvertTranscoder(mediaConvert, sourceBucket, deliveryBucket, roleArn, queueArn, qvbrQuality);
    }
}
