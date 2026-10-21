package org.example.ohara.auth.infrastructure.persistence;

import org.example.ohara.auth.domain.OneTimeToken;
import org.example.ohara.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OneTimeTokenRepository extends JpaRepository<OneTimeToken, UUID> {

    Optional<OneTimeToken> findByTokenHashAndPurpose(String tokenHash, OneTimeToken.Purpose purpose);

    List<OneTimeToken> findByUserAndPurposeAndConsumedAtIsNull(User user, OneTimeToken.Purpose purpose);
}
