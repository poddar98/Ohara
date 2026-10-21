package org.example.ohara.auth.infrastructure.persistence;

import org.example.ohara.auth.domain.PasswordCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordCredentialRepository extends JpaRepository<PasswordCredential, UUID> {
    Optional<PasswordCredential> findByAuthIdentityProviderAndAuthIdentityIdentifier(String provider, String identifier);
}
