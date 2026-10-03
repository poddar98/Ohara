package org.example.ohara.user.infrastructure.persistence;

import org.example.ohara.user.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {
    Optional<UserProfile> findByUserEmail(String email);
    boolean existsByUserEmail(String email);
}
