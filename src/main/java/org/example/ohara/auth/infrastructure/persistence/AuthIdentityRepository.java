package org.example.ohara.auth.infrastructure.persistence;

import org.example.ohara.auth.domain.AuthIdentity;
import org.example.ohara.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, UUID> {
    Optional<AuthIdentity> findByUserAndProvider(User user, String provider);
}
