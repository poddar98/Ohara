package org.example.ohara.live.application;

import org.example.ohara.live.domain.LiveChannel;
import org.example.ohara.live.domain.LiveSession;
import org.example.ohara.live.infrastructure.persistence.LiveChannelRepository;
import org.example.ohara.live.infrastructure.persistence.LiveSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Stream state changes reported by the provider (IVS: EventBridge "Stream State Change"), plus the sweeper for
 * sessions left live. Encoders drop and reconnect, so a disconnect only marks the stream offline; the session ends
 * if it stays offline past the reconnect grace, when the host ends it, or when it overruns its slot by too much.
 * All handlers are idempotent: providers deliver at least once and out of order.
 */
@Service
public class LiveStreamEvents {

    private static final Logger log = LoggerFactory.getLogger(LiveStreamEvents.class);

    private final LiveChannelRepository channels;
    private final LiveSessionRepository sessions;
    private final LiveLifecycle lifecycle;
    private final LiveStreamingProvider provider;
    private final Duration streamEarly;
    private final Duration reconnectGrace;
    private final Duration maxOverrun;

    public LiveStreamEvents(LiveChannelRepository channels, LiveSessionRepository sessions, LiveLifecycle lifecycle,
                            LiveStreamingProvider provider,
                            @Value("${ohara.live.stream-early:30m}") Duration streamEarly,
                            @Value("${ohara.live.reconnect-grace:10m}") Duration reconnectGrace,
                            @Value("${ohara.live.max-overrun:60m}") Duration maxOverrun) {
        this.channels = channels;
        this.sessions = sessions;
        this.lifecycle = lifecycle;
        this.provider = provider;
        this.streamEarly = streamEarly;
        this.reconnectGrace = reconnectGrace;
        this.maxOverrun = maxOverrun;
    }

    /**
     * The encoder connected. Resumes a live session on the channel, or starts the upcoming one whose slot contains
     * {@code at} (from {@code streamEarly} before its start). Returns false for a stream no session accounts for,
     * which the caller should stop.
     */
    @Transactional
    public boolean streamStarted(String channelRef, Instant at) {
        Optional<LiveChannel> channel = channels.findByChannelRef(channelRef);
        if (channel.isEmpty()) {
            log.warn("Stream started on unknown channel {}", channelRef);
            return false;
        }
        List<LiveSession> live = sessions.findByChannelIdAndStatus(channel.get().getId(), LiveSession.Status.LIVE);
        if (!live.isEmpty()) {
            live.forEach(s -> s.streamStarted(at));
            return true;
        }
        Optional<LiveSession> due = sessions.findByChannelIdAndStatusInOrderByScheduledStart(channel.get().getId(),
                EnumSet.of(LiveSession.Status.SCHEDULED, LiveSession.Status.READY)).stream()
            .filter(s -> !at.isBefore(s.getScheduledStart().minus(streamEarly)) && at.isBefore(s.getScheduledEnd()))
            .findFirst();
        if (due.isEmpty()) {
            log.warn("Unscheduled stream on channel {}; it will be stopped", channelRef);
            return false;
        }
        due.get().streamStarted(at);
        return true;
    }

    @Transactional
    public void streamEnded(String channelRef, Instant at) {
        channels.findByChannelRef(channelRef).ifPresent(channel ->
            sessions.findByChannelIdAndStatus(channel.getId(), LiveSession.Status.LIVE).forEach(s -> s.streamStopped(at)));
    }

    /** Ends sessions offline past the reconnect grace (at the moment they went offline) and stops overrunning ones. */
    @Transactional
    public int sweep(Instant now) {
        int ended = 0;
        for (LiveSession session : sessions.findByStatus(LiveSession.Status.LIVE)) {
            if (session.getStreamOfflineSince() != null && session.getStreamOfflineSince().plus(reconnectGrace).isBefore(now)) {
                lifecycle.end(session, session.getStreamOfflineSince(), false);
                ended++;
            } else if (session.getScheduledEnd().plus(maxOverrun).isBefore(now)) {
                lifecycle.end(session, now, true);
                ended++;
            }
        }
        return ended;
    }
}
