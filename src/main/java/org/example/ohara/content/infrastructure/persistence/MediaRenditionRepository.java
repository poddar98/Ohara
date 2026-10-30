package org.example.ohara.content.infrastructure.persistence;

import org.example.ohara.content.domain.MediaRendition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MediaRenditionRepository extends JpaRepository<MediaRendition, UUID> {

    List<MediaRendition> findByContentAssetIdAndProcessingGenerationOrderByHeightDesc(UUID assetId, int generation);
}
