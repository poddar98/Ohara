package org.example.ohara.media.infrastructure.persistence;

import org.example.ohara.media.domain.PlaybackSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlaybackSessionRepository extends JpaRepository<PlaybackSession, UUID> {

    Optional<PlaybackSession> findByIdAndUserId(UUID id, UUID userId);
}
