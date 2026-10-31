package org.example.ohara.live.infrastructure.ivs;

import io.jsonwebtoken.Jwts;
import org.example.ohara.live.application.LiveStreamingProvider;
import software.amazon.awssdk.services.ivs.IvsClient;
import software.amazon.awssdk.services.ivs.model.ChannelLatencyMode;
import software.amazon.awssdk.services.ivs.model.ChannelNotBroadcastingException;
import software.amazon.awssdk.services.ivs.model.CreateChannelResponse;
import software.amazon.awssdk.services.ivs.model.StreamKeySummary;
import software.amazon.awssdk.services.ivschat.IvschatClient;

import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LiveStreamingProvider} on Amazon IVS low-latency channels and IVS Chat (docs 06 §7). Channels are private
 * ({@code authorized}): players need a per-viewer ES384 token signed with our playback key, whose public half is
 * imported into IVS as a playback key pair. Every channel records to S3 through {@code recordingConfigurationArn}.
 * Stream keys are read from IVS when the host asks and never stored.
 */
public class IvsLiveProvider implements LiveStreamingProvider {

    private final IvsClient ivs;
    private final IvschatClient chat;
    private final String recordingConfigurationArn;
    private final String sdType;
    private final String hdType;
    private final PrivateKey playbackKey;
    private final String playbackOrigin;
    private final Map<String, String> playbackUrls = new ConcurrentHashMap<>();

    public IvsLiveProvider(IvsClient ivs, IvschatClient chat, String recordingConfigurationArn, String sdType, String hdType,
                           PrivateKey playbackKey, String playbackOrigin) {
        this.ivs = ivs;
        this.chat = chat;
        this.recordingConfigurationArn = recordingConfigurationArn;
        this.sdType = sdType;
        this.hdType = hdType;
        this.playbackKey = playbackKey;
        this.playbackOrigin = playbackOrigin == null || playbackOrigin.isBlank() ? null : playbackOrigin;
    }

    @Override
    public String name() {
        return "IVS";
    }

    @Override
    public Channel createChannel(String name, boolean hd) {
        CreateChannelResponse created = ivs.createChannel(r -> r
            .name(name)
            .type(hd ? hdType : sdType)
            .latencyMode(ChannelLatencyMode.LOW)
            .authorized(true)
            .recordingConfigurationArn(recordingConfigurationArn));
        String arn = created.channel().arn();
        playbackUrls.put(arn, created.channel().playbackUrl());
        String room = chat.createRoom(r -> r.name(name)).arn();
        return new Channel(arn, "rtmps://" + created.channel().ingestEndpoint() + ":443/app/", room);
    }

    @Override
    public void setChannelType(String channelRef, boolean hd) {
        ivs.updateChannel(r -> r.arn(channelRef).type(hd ? hdType : sdType));
    }

    @Override
    public String streamKey(String channelRef) {
        StreamKeySummary key = ivs.listStreamKeys(r -> r.channelArn(channelRef)).streamKeys().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Channel " + channelRef + " has no stream key"));
        return ivs.getStreamKey(r -> r.arn(key.arn())).streamKey().value();
    }

    @Override
    public PlaybackGrant playback(String channelRef, UUID viewerId, Instant expiresAt) {
        String url = playbackUrls.computeIfAbsent(channelRef, arn -> ivs.getChannel(r -> r.arn(arn)).channel().playbackUrl());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aws:channel-arn", channelRef);
        claims.put("aws:viewer-id", viewerId.toString());
        if (playbackOrigin != null) {
            claims.put("aws:access-control-allow-origin", playbackOrigin);
        }
        String token = Jwts.builder()
            .header().add("typ", "JWT").and()
            .claims(claims)
            .expiration(Date.from(expiresAt))
            .signWith(playbackKey, Jwts.SIG.ES384)
            .compact();
        return new PlaybackGrant(url, token, expiresAt);
    }

    /** IVS Chat sessions last 1–180 minutes; the token is used to connect within its own short validity. */
    @Override
    public String chatToken(String chatRoomRef, UUID userId, String displayName, Set<ChatCapability> capabilities, Instant expiresAt) {
        long minutes = Duration.between(Instant.now(), expiresAt).toMinutes();
        int session = (int) Math.max(1, Math.min(180, minutes));
        return chat.createChatToken(r -> r
            .roomIdentifier(chatRoomRef)
            .userId(userId.toString())
            .capabilitiesWithStrings(capabilities.stream().map(Enum::name).toList())
            .sessionDurationInMinutes(session)
            .attributes(Map.of("displayName", displayName))).token();
    }

    @Override
    public void stopStream(String channelRef) {
        try {
            ivs.stopStream(r -> r.channelArn(channelRef));
        } catch (ChannelNotBroadcastingException alreadyOff) {
            // idempotent: nothing to stop
        }
    }
}
