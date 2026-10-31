package org.example.ohara.live.infrastructure.ivs;

import org.example.ohara.live.application.LiveStreamEvents;
import org.example.ohara.live.application.LiveStreamingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ivs.IvsClient;
import software.amazon.awssdk.services.ivschat.IvschatClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Amazon IVS when {@code ohara.live.provider=ivs}. The playback signing key is an EC P-384 key in PKCS#8 PEM (from
 * {@code playback-key-path} or {@code playback-key}); its public key is imported into IVS as a playback key pair.
 * Channel types follow docs 06 §7.3: ADVANCED_SD by default, STANDARD for sessions that need HD. Worker instances
 * also poll {@code events-queue-url} for stream and recording events.
 */
@Configuration
@ConditionalOnProperty(name = "ohara.live.provider", havingValue = "ivs")
class IvsConfig {

    private final Region region;

    IvsConfig(@Value("${ohara.aws.region:ap-south-1}") String region) {
        this.region = Region.of(region);
    }

    @Bean(destroyMethod = "close")
    IvsClient ivsClient() {
        return IvsClient.builder().region(region).credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    @Bean(destroyMethod = "close")
    IvschatClient ivschatClient() {
        return IvschatClient.builder().region(region).credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    @Bean
    LiveStreamingProvider liveStreamingProvider(IvsClient ivs, IvschatClient chat,
                                                @Value("${ohara.live.ivs.recording-configuration-arn}") String recordingArn,
                                                @Value("${ohara.live.ivs.sd-channel-type:ADVANCED_SD}") String sdType,
                                                @Value("${ohara.live.ivs.hd-channel-type:STANDARD}") String hdType,
                                                @Value("${ohara.live.ivs.playback-key-path:}") String keyPath,
                                                @Value("${ohara.live.ivs.playback-key:}") String keyPem,
                                                @Value("${ohara.live.ivs.playback-origin:}") String origin) throws IOException {
        String pem = !keyPem.isBlank() ? keyPem : Files.readString(Path.of(keyPath));
        return new IvsLiveProvider(ivs, chat, recordingArn, sdType, hdType, playbackKey(pem), origin);
    }

    @Configuration
    @ConditionalOnProperty(name = "ohara.jobs.runner-enabled", havingValue = "true", matchIfMissing = true)
    static class EventIntake {

        @Bean(destroyMethod = "close")
        SqsClient ivsEventsSqsClient(@Value("${ohara.aws.region:ap-south-1}") String region) {
            return SqsClient.builder().region(Region.of(region)).credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        }

        @Bean
        SqsEventPoller ivsEventPoller(SqsClient sqs, LiveStreamEvents events, LiveStreamingProvider provider, JsonMapper json,
                                      @Value("${ohara.live.ivs.events-queue-url}") String queueUrl,
                                      @Value("${ohara.storage.s3.source-bucket}") String recordingBucket) {
            return new SqsEventPoller(sqs, queueUrl, new IvsEventIntake(events, provider, json, recordingBucket), 20);
        }
    }

    static PrivateKey playbackKey(String pem) {
        if (pem.contains("BEGIN EC PRIVATE KEY")) {
            throw new IllegalStateException("IVS playback key is SEC1; convert it with: openssl pkcs8 -topk8 -nocrypt");
        }
        String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            throw new IllegalStateException("IVS playback key is not a valid PKCS#8 EC private key", e);
        }
    }
}
