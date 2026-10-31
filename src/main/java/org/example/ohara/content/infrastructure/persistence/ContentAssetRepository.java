package org.example.ohara.content.infrastructure.persistence;

import org.example.ohara.content.domain.ContentAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ContentAssetRepository extends JpaRepository<ContentAsset, UUID> {

    @Query("""
        select a from ContentAsset a
        where ((:orgId is null and a.ownerOrganizationId is null) or a.ownerOrganizationId = :orgId)
          and (:type is null or a.type = :type)
          and (:status is null or a.status = :status)
        order by a.createdAt desc, a.id
        """)
    Page<ContentAsset> library(@Param("orgId") UUID ownerOrganizationId, @Param("type") ContentAsset.Type type,
                               @Param("status") ContentAsset.Status status, Pageable pageable);

    java.util.Optional<ContentAsset> findByLiveSessionId(UUID liveSessionId);

    @Query(value = "select count(*) from lesson_content where content_asset_id = :assetId", nativeQuery = true)
    long countLessonUses(@Param("assetId") UUID assetId);
}
