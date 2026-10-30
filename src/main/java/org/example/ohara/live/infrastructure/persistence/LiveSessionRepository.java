package org.example.ohara.live.infrastructure.persistence;

import org.example.ohara.live.domain.LiveSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveSessionRepository extends JpaRepository<LiveSession, UUID> {

    Optional<LiveSession> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("""
        select s from LiveSession s
        where s.organizationId = :organizationId and (:cohortId is null or s.cohortId = :cohortId)
          and s.scheduledEnd >= :from and s.scheduledStart < :to
        order by s.scheduledStart""")
    List<LiveSession> findForOrganization(UUID organizationId, UUID cohortId, Instant from, Instant to);

    @Query("""
        select s from LiveSession s
        where s.offeringId in :offeringIds and s.scheduledEnd >= :from and s.scheduledStart < :to
        order by s.scheduledStart""")
    List<LiveSession> findForOfferings(Collection<UUID> offeringIds, Instant from, Instant to);

    List<LiveSession> findByChannelIdAndStatus(UUID channelId, LiveSession.Status status);

    List<LiveSession> findByChannelIdAndStatusInOrderByScheduledStart(UUID channelId, Collection<LiveSession.Status> statuses);

    List<LiveSession> findByStatus(LiveSession.Status status);
}
