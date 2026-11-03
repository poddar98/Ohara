package org.example.ohara.content.infrastructure.aws;

import org.example.ohara.content.application.CdnSigner;
import org.example.ohara.content.application.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the AWS providers selected, the application runs on S3, CloudFront, IVS and Transcribe, and the local stand-ins (with
 * their /dev-storage, /dev-cdn and /dev-live endpoints) are gone. (The SQS event poller only runs on job workers.)
 */
@SpringBootTest(properties = {
    "ohara.storage.provider=s3",
    "ohara.storage.s3.source-bucket=ohara-source",
    "ohara.storage.s3.delivery-bucket=ohara-delivery",
    "ohara.storage.s3.endpoint=http://localhost:1",
    "ohara.storage.s3.access-key=test",
    "ohara.storage.s3.secret-key=test",
    "ohara.cdn.provider=cloudfront",
    "ohara.cdn.cloudfront.domain=media.example.in",
    "ohara.cdn.cloudfront.key-pair-id=K2JCJMDEHXQW5F",
    "ohara.cdn.cookie-domain=.example.in",
    "ohara.live.provider=ivs",
    "ohara.live.ivs.recording-configuration-arn=arn:aws:ivs:ap-south-1:123456789012:recording-configuration/Rec1",
    "ohara.ai.stt.provider=transcribe",
    "ohara.media.mediaconvert.role-arn=arn:aws:iam::123456789012:role/ohara-mediaconvert"})
class AwsWiringTest {

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + java.util.Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----";
        registry.add("ohara.cdn.cloudfront.private-key", () -> pem);
        var ec = java.security.KeyPairGenerator.getInstance("EC");
        ec.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"));
        String playbackPem = "-----BEGIN PRIVATE KEY-----\n"
            + java.util.Base64.getEncoder().encodeToString(ec.generateKeyPair().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
        registry.add("ohara.live.ivs.playback-key", () -> playbackPem);
    }

    @Autowired ObjectStorage storage;
    @Autowired CdnSigner cdn;
    @Autowired org.example.ohara.live.application.LiveStreamingProvider live;
    @Autowired org.example.ohara.ai.application.SpeechToText stt;
    @Autowired ApplicationContext context;

    @Test
    void transcribeReplacesTheLocalSpeechToText() {
        assertThat(stt).isInstanceOf(org.example.ohara.ai.infrastructure.aws.TranscribeSpeechToText.class);
        assertThat(stt.provider()).isEqualTo("AWS_TRANSCRIBE");
    }

    @Test
    void s3ReplacesTheLocalStandIn() {
        assertThat(storage).isInstanceOf(S3ObjectStorage.class);
        assertThat(((S3ObjectStorage) storage).bucketOf("delivery/org/x/master.m3u8")).isEqualTo("ohara-delivery");
        assertThat(((S3ObjectStorage) storage).bucketOf("org/x/asset/y/source/a.mp4")).isEqualTo("ohara-source");
        assertThat(context.getBeanNamesForType(org.example.ohara.content.infrastructure.storage.LocalObjectStorage.class)).isEmpty();
    }

    @Test
    void ivsReplacesTheLocalLiveProvider() {
        assertThat(live).isInstanceOf(org.example.ohara.live.infrastructure.ivs.IvsLiveProvider.class);
        assertThat(live.name()).isEqualTo("IVS");
        assertThat(context.getBeanNamesForType(org.example.ohara.live.infrastructure.provider.LocalLiveProvider.class)).isEmpty();
    }

    @Test
    void cloudFrontReplacesTheLocalCdn() {
        assertThat(cdn).isInstanceOf(CloudFrontCdnSigner.class);
        assertThat(cdn.urlOf("delivery/x/master.m3u8")).isEqualTo("https://media.example.in/delivery/x/master.m3u8");
        assertThat(context.getBeanNamesForType(org.example.ohara.content.infrastructure.cdn.LocalCdn.class)).isEmpty();
    }
}
