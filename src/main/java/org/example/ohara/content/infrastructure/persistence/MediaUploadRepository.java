package org.example.ohara.content.infrastructure.persistence;

import org.example.ohara.content.domain.MediaUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaUploadRepository extends JpaRepository<MediaUpload, UUID> {

    Optional<MediaUpload> findByInitiatedByAndClientKey(UUID initiatedBy, String clientKey);

    Optional<MediaUpload> findByObjectKeyAndStatus(String objectKey, MediaUpload.Status status);

    @Query("select u from MediaUpload u where u.status = 'INITIATED' and u.expiresAt < :now")
    List<MediaUpload> findExpired(@Param("now") Instant now);
}
