package org.example.ohara.live.infrastructure.ivs;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.example.ohara.live.application.LiveStreamingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ivs.IvsClient;
import software.amazon.awssdk.services.ivs.model.Channel;
import software.amazon.awssdk.services.ivs.model.ChannelNotBroadcastingException;
import software.amazon.awssdk.services.ivs.model.CreateChannelRequest;
import software.amazon.awssdk.services.ivs.model.CreateChannelResponse;
import software.amazon.awssdk.services.ivs.model.GetChannelRequest;
import software.amazon.awssdk.services.ivs.model.GetChannelResponse;
import software.amazon.awssdk.services.ivs.model.GetStreamKeyRequest;
import software.amazon.awssdk.services.ivs.model.GetStreamKeyResponse;
import software.amazon.awssdk.services.ivs.model.ListStreamKeysRequest;
import software.amazon.awssdk.services.ivs.model.ListStreamKeysResponse;
import software.amazon.awssdk.services.ivs.model.StopStreamRequest;
import software.amazon.awssdk.services.ivs.model.StreamKey;
import software.amazon.awssdk.services.ivs.model.StreamKeySummary;
import software.amazon.awssdk.services.ivs.model.UpdateChannelRequest;
import software.amazon.awssdk.services.ivs.model.UpdateChannelResponse;
import software.amazon.awssdk.services.ivschat.IvschatClient;
import software.amazon.awssdk.services.ivschat.model.CreateChatTokenRequest;
import software.amazon.awssdk.services.ivschat.model.CreateChatTokenResponse;
import software.amazon.awssdk.services.ivschat.model.CreateRoomRequest;
import software.amazon.awssdk.services.ivschat.model.CreateRoomResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

class IvsLiveProviderTest {

    static final String CHANNEL = "arn:aws:ivs:ap-south-1:123456789012:channel/AbCdEf123";

    IvsClient ivs;
    IvschatClient chat;
    KeyPair keys;
    IvsLiveProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        ivs = mock(IvsClient.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        chat = mock(IvschatClient.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        keys = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----";
        provider = new IvsLiveProvider(ivs, chat, "arn:aws:ivs:ap-south-1:123456789012:recording-configuration/Rec1",
            "ADVANCED_SD", "STANDARD", IvsConfig.playbackKey(pem), "https://app.example.in");
    }

    @Test
    void aCohortGetsAPrivateRecordedLowLatencyChannelAndAChatRoom() {
        doReturn(CreateChannelResponse.builder().channel(Channel.builder().arn(CHANNEL)
                .ingestEndpoint("f99084460c35.global-contribute.live-video.net")
                .playbackUrl("https://f99084460c35.ap-south-1.playback.live-video.net/api/video/v1/x.m3u8").build()).build())
            .when(ivs).createChannel(any(CreateChannelRequest.class));
        doReturn(CreateRoomResponse.builder().arn("arn:aws:ivschat:ap-south-1:123456789012:room/Room1").build())
            .when(chat).createRoom(any(CreateRoomRequest.class));

        LiveStreamingProvider.Channel channel = provider.createChannel("cohort-1", false);

        assertThat(channel.channelRef()).isEqualTo(CHANNEL);
        assertThat(channel.ingestEndpoint()).isEqualTo("rtmps://f99084460c35.global-contribute.live-video.net:443/app/");
        assertThat(channel.chatRoomRef()).endsWith("room/Room1");
        ArgumentCaptor<CreateChannelRequest> request = ArgumentCaptor.forClass(CreateChannelRequest.class);
        verify(ivs).createChannel(request.capture());
        assertThat(request.getValue().authorized()).isTrue();
        assertThat(request.getValue().typeAsString()).isEqualTo("ADVANCED_SD");
        assertThat(request.getValue().latencyModeAsString()).isEqualTo("LOW");
        assertThat(request.getValue().recordingConfigurationArn()).endsWith("recording-configuration/Rec1");

        doReturn(UpdateChannelResponse.builder().build()).when(ivs).updateChannel(any(UpdateChannelRequest.class));
        provider.setChannelType(CHANNEL, true);
        ArgumentCaptor<UpdateChannelRequest> update = ArgumentCaptor.forClass(UpdateChannelRequest.class);
        verify(ivs).updateChannel(update.capture());
        assertThat(update.getValue().typeAsString()).isEqualTo("STANDARD");
    }

    @Test
    void playbackTokensAreES384SignedForOneViewerOfOneChannel() {
        doReturn(GetChannelResponse.builder().channel(Channel.builder().arn(CHANNEL)
                .playbackUrl("https://x.playback.live-video.net/api/video/v1/x.m3u8").build()).build())
            .when(ivs).getChannel(any(GetChannelRequest.class));
        UUID viewer = UUID.randomUUID();
        Instant expires = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        LiveStreamingProvider.PlaybackGrant grant = provider.playback(CHANNEL, viewer, expires);

        assertThat(grant.playbackUrl()).endsWith("x.m3u8");
        var parsed = Jwts.parser().verifyWith(keys.getPublic()).build().parseSignedClaims(grant.token());
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("ES384");
        Claims claims = parsed.getPayload();
        assertThat(claims.get("aws:channel-arn")).isEqualTo(CHANNEL);
        assertThat(claims.get("aws:viewer-id")).isEqualTo(viewer.toString());
        assertThat(claims.get("aws:access-control-allow-origin")).isEqualTo("https://app.example.in");
        assertThat(claims.getExpiration().toInstant()).isEqualTo(expires);
    }

    @Test
    void chatTokensCarryCapabilitiesAndStreamKeysComeFromIvs() {
        doReturn(CreateChatTokenResponse.builder().token("chat-token").build()).when(chat).createChatToken(any(CreateChatTokenRequest.class));
        String token = provider.chatToken("room-arn", UUID.randomUUID(), "Asha Rao",
            EnumSet.of(LiveStreamingProvider.ChatCapability.SEND_MESSAGE, LiveStreamingProvider.ChatCapability.DELETE_MESSAGE),
            Instant.now().plus(6, ChronoUnit.HOURS));
        assertThat(token).isEqualTo("chat-token");
        ArgumentCaptor<CreateChatTokenRequest> request = ArgumentCaptor.forClass(CreateChatTokenRequest.class);
        verify(chat).createChatToken(request.capture());
        assertThat(request.getValue().capabilitiesAsStrings()).containsExactlyInAnyOrder("SEND_MESSAGE", "DELETE_MESSAGE");
        assertThat(request.getValue().sessionDurationInMinutes()).isEqualTo(180); // IVS Chat's maximum
        assertThat(request.getValue().attributes()).containsEntry("displayName", "Asha Rao");

        doReturn(ListStreamKeysResponse.builder().streamKeys(StreamKeySummary.builder().arn("key-arn").build()).build())
            .when(ivs).listStreamKeys(any(ListStreamKeysRequest.class));
        doReturn(GetStreamKeyResponse.builder().streamKey(StreamKey.builder().value("sk_ap-south-1_secret").build()).build())
            .when(ivs).getStreamKey(any(GetStreamKeyRequest.class));
        assertThat(provider.streamKey(CHANNEL)).isEqualTo("sk_ap-south-1_secret");
    }

    @Test
    void stoppingAnIdleChannelIsHarmlessAndBadKeysFailFast() {
        doThrow(ChannelNotBroadcastingException.builder().message("not live").build()).when(ivs).stopStream(any(StopStreamRequest.class));
        provider.stopStream(CHANNEL);

        assertThatThrownBy(() -> IvsConfig.playbackKey("-----BEGIN EC PRIVATE KEY-----\nabc\n-----END EC PRIVATE KEY-----"))
            .hasMessageContaining("openssl pkcs8");
    }
}
