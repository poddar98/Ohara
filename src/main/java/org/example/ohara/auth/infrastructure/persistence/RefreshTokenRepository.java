package org.example.ohara.auth.infrastructure.persistence;

import org.example.ohara.auth.domain.RefreshToken;
import org.example.ohara.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findByUserAndRevokedAtIsNull(User user);
}
