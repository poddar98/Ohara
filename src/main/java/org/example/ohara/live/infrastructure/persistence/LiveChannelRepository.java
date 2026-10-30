package org.example.ohara.live.infrastructure.persistence;

import org.example.ohara.live.domain.LiveChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LiveChannelRepository extends JpaRepository<LiveChannel, UUID> {

    Optional<LiveChannel> findByCohortId(UUID cohortId);

    Optional<LiveChannel> findByChannelRef(String channelRef);
}
