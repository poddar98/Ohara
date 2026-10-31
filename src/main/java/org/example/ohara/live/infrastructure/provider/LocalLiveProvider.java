package org.example.ohara.live.infrastructure.provider;

import org.example.ohara.content.application.ObjectStorage;
import org.example.ohara.live.application.LiveStreamingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Stand-in for IVS + IVS Chat. Tokens are HMAC-signed (channel or room, user, expiry); the /dev-live endpoints play
 * the provider's side: serving playback to valid token holders and reporting encoder connects/disconnects.
 */
@Component
@ConditionalOnProperty(name = "ohara.live.provider", havingValue = "local", matchIfMissing = true)
public class LocalLiveProvider implements LiveStreamingProvider {

    static final String PATH = "/dev-live/";

    private final String baseUrl;
    private final ObjectStorage storage;
    private final byte[] key = new byte[32];
    private final Set<String> onAir = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Boolean> channelHd = new ConcurrentHashMap<>();

    public LocalLiveProvider(@Value("${ohara.live.local.base-url:http://localhost:8080}") String baseUrl, ObjectStorage storage) {
        this.baseUrl = baseUrl;
        this.storage = storage;
        new SecureRandom().nextBytes(key);
    }

    @Override
    public String name() {
        return "LOCAL";
    }

    @Override
    public Channel createChannel(String name, boolean hd) {
        String ref = "local-ch-" + UUID.randomUUID();
        channelHd.put(ref, hd);
        return new Channel(ref, "rtmps://localhost:4443/app/", "local-room-" + UUID.randomUUID());
    }

    @Override
    public void setChannelType(String channelRef, boolean hd) {
        if (onAir.contains(channelRef)) {
            throw new IllegalStateException("Channel is live");
        }
        channelHd.put(channelRef, hd);
    }

    @Override
    public String streamKey(String channelRef) {
        return "sk_local_" + hmac("stream-key\n" + channelRef);
    }

    @Override
    public PlaybackGrant playback(String channelRef, UUID viewerId, Instant expiresAt) {
        String token = sign("playback\n" + channelRef + "\n" + viewerId + "\n" + expiresAt.getEpochSecond());
        return new PlaybackGrant(baseUrl + PATH + "playback/" + channelRef + ".m3u8", token, expiresAt);
    }

    @Override
    public String chatToken(String chatRoomRef, UUID userId, String displayName, Set<ChatCapability> capabilities, Instant expiresAt) {
        String caps = capabilities.stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new)).toString();
        return sign("chat\n" + chatRoomRef + "\n" + userId + "\n" + caps + "\n" + expiresAt.getEpochSecond());
    }

    @Override
    public void stopStream(String channelRef) {
        onAir.remove(channelRef);
    }

    // --- the provider's own side, used by the dev endpoints ---

    void encoderConnected(String channelRef) {
        onAir.add(channelRef);
    }

    /**
     * Writes a recording the way IVS auto-record lays it out ({@code .../media/hls/master.m3u8} with 720p30 and 480p30
     * renditions of 6-second segments) and returns the master key. {@code complete=false} leaves the renditions
     * unterminated, like a recording cut off by a failure.
     */
    String writeRecording(String channelRef, int seconds, boolean complete) {
        String dir = "live/" + channelRef + "/" + UUID.randomUUID() + "/media/hls/";
        StringBuilder master = new StringBuilder("#EXTM3U\n");
        for (int[] r : new int[][] {{1280, 720, 2_500_000}, {852, 480, 1_200_000}}) {
            String rendition = r[1] + "p30/";
            StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-PLAYLIST-TYPE:EVENT\n");
            int n = 0;
            for (int left = seconds; left > 0; left -= 6) {
                String segment = (++n) + ".ts";
                playlist.append("#EXTINF:").append(Math.min(6, left)).append(".0,\n").append(segment).append('\n');
                storage.put(dir + rendition + segment, ("rec " + segment).getBytes(StandardCharsets.UTF_8), "video/mp2t");
            }
            if (complete) {
                playlist.append("#EXT-X-ENDLIST\n");
            }
            storage.put(dir + rendition + "playlist.m3u8", playlist.toString().getBytes(StandardCharsets.UTF_8), "application/vnd.apple.mpegurl");
            master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(r[2]).append(",RESOLUTION=").append(r[0]).append('x').append(r[1])
                .append('\n').append(rendition).append("playlist.m3u8\n");
        }
        storage.put(dir + "master.m3u8", master.toString().getBytes(StandardCharsets.UTF_8), "application/vnd.apple.mpegurl");
        return dir + "master.m3u8";
    }

    boolean isOnAir(String channelRef) {
        return onAir.contains(channelRef);
    }

    /** The channel a playback token unlocks, if genuine and unexpired. */
    Optional<String> verifyPlayback(String token) {
        return verify(token).filter(p -> p[0].equals("playback")).map(p -> p[1]);
    }

    /** Decoded fields of a genuine chat token: room, user, capabilities. */
    public Optional<String[]> verifyChat(String token) {
        return verify(token).filter(p -> p[0].equals("chat")).map(p -> new String[] {p[1], p[2], p[3]});
    }

    private String sign(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + hmac(payload);
    }

    private Optional<String[]> verify(String token) {
        try {
            int dot = token.lastIndexOf('.');
            String payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(hmac(payload).getBytes(StandardCharsets.UTF_8),
                    token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }
            String[] parts = payload.split("\n");
            return Instant.now().getEpochSecond() <= Long.parseLong(parts[parts.length - 1]) ? Optional.of(parts) : Optional.empty();
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
