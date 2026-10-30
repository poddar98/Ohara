package org.example.ohara.content.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.content.domain.ContentAsset;
import org.example.ohara.organization.application.OrgAuthorizationService;
import org.example.ohara.organization.application.OrgPermission;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Asset library permissions: an organization's authors (admins, instructors) manage its assets; platform admins manage
 * platform assets (and may act anywhere); platform support may read. Everyone else gets 404.
 */
@Component
class AssetAccess {

    private final OrgAuthorizationService orgAuthorization;

    AssetAccess(OrgAuthorizationService orgAuthorization) {
        this.orgAuthorization = orgAuthorization;
    }

    void requireManageOwner(CurrentActor actor, UUID ownerOrganizationId) {
        if (ownerOrganizationId == null) {
            if (!actor.isPlatformAdmin()) {
                throw new ApiException.Forbidden("forbidden", "Only platform admins manage platform assets");
            }
            return;
        }
        orgAuthorization.require(actor, ownerOrganizationId, OrgPermission.AUTHOR_COURSES);
    }

    boolean canManage(CurrentActor actor, ContentAsset asset) {
        return actor.isPlatformAdmin() || (asset.getOwnerOrganizationId() != null
            && orgAuthorization.allows(actor, asset.getOwnerOrganizationId(), OrgPermission.AUTHOR_COURSES));
    }

    void requireManage(CurrentActor actor, ContentAsset asset) {
        if (!canManage(actor, asset)) {
            if (canRead(actor, asset)) {
                throw new ApiException.Forbidden("forbidden", "Read-only access");
            }
            throw notFound();
        }
    }

    boolean canRead(CurrentActor actor, ContentAsset asset) {
        return canManage(actor, asset) || actor.isPlatformSupport();
    }

    void requireRead(CurrentActor actor, ContentAsset asset) {
        if (!canRead(actor, asset)) {
            throw notFound();
        }
    }

    static ApiException notFound() {
        return new ApiException.NotFound("asset_not_found", "Asset not found");
    }
}
