package org.example.ohara.live.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.example.ohara.live.domain.LiveAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveAttendanceRepository extends JpaRepository<LiveAttendance, LiveAttendance.Key> {

    /** Creates the row if missing without failing when a concurrent join already did. */
    @Modifying
    @Query(value = "INSERT INTO live_attendance (live_session_id, user_id) VALUES (:sessionId, :userId) ON CONFLICT DO NOTHING",
        nativeQuery = true)
    void ensureExists(UUID sessionId, UUID userId);

    /** Locked so concurrent heartbeats from two tabs can't both credit the same interval. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LiveAttendance a where a.sessionId = :sessionId and a.userId = :userId")
    Optional<LiveAttendance> findForUpdate(UUID sessionId, UUID userId);

    List<LiveAttendance> findBySessionIdOrderByUserId(UUID sessionId);
}
