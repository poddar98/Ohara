package org.example.ohara.live.infrastructure.ivs;

import org.example.ohara.live.application.LiveStreamEvents;
import org.example.ohara.live.application.LiveStreamingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** EventBridge-shaped IVS events through a real SQS queue (LocalStack) into LiveStreamEvents. */
@Testcontainers(disabledWithoutDocker = true)
class IvsEventIntakeTest {

    static final String CHANNEL = "arn:aws:ivs:ap-south-1:123456789012:channel/AbCdEf123";

    @Container
    static final GenericContainer<?> LOCALSTACK = new GenericContainer<>(DockerImageName.parse("localstack/localstack:4.9"))
        .withEnv("SERVICES", "sqs")
        .withExposedPorts(4566)
        .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566)
            .forResponsePredicate(body -> body.contains("\"sqs\": \"available\"") || body.contains("\"sqs\": \"running\""))
            .withStartupTimeout(Duration.ofMinutes(2)));

    static SqsClient sqs;
    String queueUrl;
    LiveStreamEvents events;
    LiveStreamingProvider provider;
    SqsEventPoller poller;

    @BeforeAll
    static void client() {
        sqs = SqsClient.builder().region(Region.US_EAST_1)
            .endpointOverride(URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566)))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    @BeforeEach
    void queue() {
        queueUrl = sqs.createQueue(r -> r.queueName("ivs-events-" + System.nanoTime())
            .attributes(java.util.Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "30"))).queueUrl();
        events = mock(LiveStreamEvents.class);
        provider = mock(LiveStreamingProvider.class);
        poller = new SqsEventPoller(sqs, queueUrl, new IvsEventIntake(events, provider, JsonMapper.builder().build(), "ohara-source"), 1);
    }

    private void send(String body) {
        sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody(body));
    }

    private static String streamEvent(String name, String time) {
        return """
            {"version":"0","id":"6f3b","detail-type":"IVS Stream State Change","source":"aws.ivs","account":"123456789012",
             "time":"%s","region":"ap-south-1","resources":["%s"],
             "detail":{"event_name":"%s","channel_name":"cohort-1","stream_id":"st-1AbC"}}""".formatted(time, CHANNEL, name);
    }

    private int messagesLeft() {
        var attributes = sqs.getQueueAttributes(r -> r.queueUrl(queueUrl).attributeNames(
            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
        return Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
            + Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }

    @Test
    void streamAndRecordingEventsReachTheSessionLogicAndAreAcknowledged() {
        when(events.streamStarted(eq(CHANNEL), any())).thenReturn(true);
        send(streamEvent("Stream Start", "2026-10-31T10:00:00Z"));
        send(streamEvent("Stream End", "2026-10-31T11:00:00Z"));
        send("""
            {"version":"0","id":"9a1c","detail-type":"IVS Recording State Change","source":"aws.ivs","account":"123456789012",
             "time":"2026-10-31T11:00:30Z","region":"ap-south-1","resources":["%s"],
             "detail":{"channel_name":"cohort-1","stream_id":"st-1AbC","recording_status":"Recording End",
                       "recording_status_reason":"","recording_s3_bucket_name":"ohara-source",
                       "recording_s3_key_prefix":"ivs/v1/123456789012/AbCdEf123/2026/10/31/10/0/rec1",
                       "recording_duration_ms":3600000,"recording_session_id":"rs-1"}}""".formatted(CHANNEL));
        send("{\"detail-type\":\"IVS Limit Breach\",\"detail\":{}}");

        int handled = 0;
        for (int i = 0; i < 5 && handled < 4; i++) {
            handled += poller.pollOnce();
        }

        assertThat(handled).isEqualTo(4);
        verify(events).streamStarted(CHANNEL, Instant.parse("2026-10-31T10:00:00Z"));
        verify(events).streamEnded(CHANNEL, Instant.parse("2026-10-31T11:00:00Z"));
        verify(events).recordingEnded(CHANNEL, "ivs/v1/123456789012/AbCdEf123/2026/10/31/10/0/rec1/media/hls/master.m3u8",
            Instant.parse("2026-10-31T10:00:30Z"), Instant.parse("2026-10-31T11:00:30Z"));
        verify(provider, never()).stopStream(anyString());
        assertThat(messagesLeft()).isZero();
    }

    @Test
    void anUnscheduledBroadcastIsStoppedAndFailuresStayQueuedForRetry() {
        when(events.streamStarted(eq(CHANNEL), any())).thenReturn(false);
        send(streamEvent("Stream Start", "2026-10-31T03:00:00Z"));
        while (poller.pollOnce() == 0) { }
        verify(provider).stopStream(CHANNEL);

        doThrow(new IllegalStateException("database unavailable")).when(events).streamEnded(anyString(), any());
        send(streamEvent("Stream End", "2026-10-31T03:05:00Z"));
        for (int i = 0; i < 3; i++) {
            assertThat(poller.pollOnce()).isZero();
        }
        assertThat(messagesLeft()).isEqualTo(1); // invisible for now; redelivered later, then dead-lettered by the queue's policy
    }
}
