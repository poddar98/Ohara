package org.example.ohara.user.infrastructure.persistence;

import org.example.ohara.user.domain.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {

    /** Latest decision per consent type. */
    @Query("""
        select c from UserConsent c where c.userId = :userId and c.recordedAt = (
            select max(c2.recordedAt) from UserConsent c2 where c2.userId = c.userId and c2.type = c.type)
        """)
    List<UserConsent> findCurrent(@Param("userId") UUID userId);
}
