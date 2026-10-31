package org.example.ohara.organization.application;

import org.example.ohara.organization.domain.CohortMembership;
import org.example.ohara.organization.domain.OrganizationMembership;
import org.example.ohara.organization.infrastructure.persistence.CohortMembershipRepository;
import org.example.ohara.organization.infrastructure.persistence.OrganizationMembershipRepository;
import org.example.ohara.user.application.AccountEvents;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Removes a deleted person from organizations, in the anonymizing transaction: open cohort placements end (which
 * ends cohort-granted access the usual way), memberships become REMOVED, and roll numbers, invitation emails and names,
 * and bulk-import row details are scrubbed. Pending invitations to the old address are revoked.
 * <p>
 * The last admin of an organization can be removed this way; platform admins then appoint a new one.
 */
@Component
class MembershipAnonymization {

    private final CohortMembershipRepository placements;
    private final OrganizationMembershipRepository memberships;
    private final CohortPlacementEvents events;
    private final JdbcClient jdbc;

    MembershipAnonymization(CohortMembershipRepository placements, OrganizationMembershipRepository memberships,
                         CohortPlacementEvents events, JdbcClient jdbc) {
        this.placements = placements;
        this.memberships = memberships;
        this.events = events;
        this.jdbc = jdbc;
    }

    @EventListener
    void on(AccountEvents.Anonymizing event) {
        for (CohortMembership placement : placements.findOpenByUser(event.userId())) {
            placement.end(CohortMembership.Status.REMOVED);
            events.ended(placement);
        }
        for (OrganizationMembership.Status status : new OrganizationMembership.Status[] {
                OrganizationMembership.Status.ACTIVE, OrganizationMembership.Status.SUSPENDED}) {
            for (OrganizationMembership membership : memberships.findByUserIdAndStatus(event.userId(), status)) {
                membership.remove();
                membership.setExternalRef(null);
            }
        }
        memberships.flush();
        jdbc.sql("UPDATE organization_membership SET external_ref = NULL WHERE user_id = :u AND external_ref IS NOT NULL")
            .param("u", event.userId()).update();
        jdbc.sql("""
                UPDATE invitation
                   SET status = CASE WHEN status = 'PENDING' THEN 'REVOKED' ELSE status END,
                       revoked_at = CASE WHEN status = 'PENDING' THEN now() ELSE revoked_at END,
                       email = :scrubbed, first_name = NULL, last_name = NULL, external_ref = NULL, updated_at = now()
                 WHERE lower(email) = lower(:former) OR accepted_by_user_id = :u""")
            .param("scrubbed", "deleted+" + event.userId() + "@invalid").param("former", event.formerEmail())
            .param("u", event.userId()).update();
        jdbc.sql("""
                UPDATE bulk_import_row SET email = NULL, first_name = NULL, last_name = NULL, external_ref = NULL
                 WHERE user_id = :u OR lower(email) = lower(:former)""")
            .param("u", event.userId()).param("former", event.formerEmail()).update();
    }
}
