package org.example.ohara.organization.application;

import org.example.ohara.organization.domain.OrgRole;

import java.util.EnumSet;
import java.util.Set;

/** What an actor may do inside one organization, and which org roles grant it. */
public enum OrgPermission {
    VIEW_ORG(true, OrgRole.ORG_ADMIN, OrgRole.INSTRUCTOR, OrgRole.LEARNER),
    MANAGE_ORG(false, OrgRole.ORG_ADMIN),
    VIEW_MEMBERS(true, OrgRole.ORG_ADMIN),
    MANAGE_MEMBERS(false, OrgRole.ORG_ADMIN),
    VIEW_COHORTS(true, OrgRole.ORG_ADMIN, OrgRole.INSTRUCTOR),
    MANAGE_COHORTS(false, OrgRole.ORG_ADMIN),
    /** Create courses owned by the organization and edit those they teach. */
    AUTHOR_COURSES(false, OrgRole.ORG_ADMIN, OrgRole.INSTRUCTOR),
    /** Full control over every course the organization owns. */
    MANAGE_COURSES(false, OrgRole.ORG_ADMIN);

    private final boolean readOnly;
    private final Set<OrgRole> grantedTo;

    OrgPermission(boolean readOnly, OrgRole first, OrgRole... rest) {
        this.readOnly = readOnly;
        this.grantedTo = EnumSet.of(first, rest);
    }

    /** Platform support staff get read-only permissions in every organization. */
    public boolean isReadOnly() {
        return readOnly;
    }

    public boolean isGrantedToAny(Set<OrgRole> roles) {
        return roles.stream().anyMatch(grantedTo::contains);
    }
}
