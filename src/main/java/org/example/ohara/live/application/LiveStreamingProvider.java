package org.example.ohara.live.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Live video and chat provider port, shaped like Amazon IVS low-latency channels plus IVS Chat rooms (docs 06 §7).
 * Channels are private: viewers need a per-viewer playback token. Stream state changes arrive asynchronously through
 * {@link LiveStreamEvents} (EventBridge on IVS).
 */
public interface LiveStreamingProvider {

    record Channel(String channelRef, String ingestEndpoint, String chatRoomRef) {
    }

    record PlaybackGrant(String playbackUrl, String token, Instant expiresAt) {
    }

    enum ChatCapability { SEND_MESSAGE, DELETE_MESSAGE, DISCONNECT_USER }

    /** Short provider name stored with each channel. */
    String name();

    /** A new private channel (with recording configured) and its chat room. */
    Channel createChannel(String name, boolean hd);

    /** Switch between the cheaper standard tier and full HD; only while the channel is offline. */
    void setChannelType(String channelRef, boolean hd);

    /** The secret the host's encoder uses. Never stored by Ohara. */
    String streamKey(String channelRef);

    PlaybackGrant playback(String channelRef, UUID viewerId, Instant expiresAt);

    /** A chat token for one user, scoped to one room. */
    String chatToken(String chatRoomRef, UUID userId, String displayName, Set<ChatCapability> capabilities, Instant expiresAt);

    /** Disconnects the encoder (a host ending the class, or an overrun/unscheduled stream). */
    void stopStream(String channelRef);
}
