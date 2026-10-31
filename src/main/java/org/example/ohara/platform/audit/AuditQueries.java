package org.example.ohara.platform.audit;

import org.example.ohara.common.web.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reading the audit trail, newest first. Callers authorize: org admins see their org's events, platform staff all. */
@Component
public class AuditQueries {

    /** {@code organizationId} null means all organizations (platform staff only). */
    public record Filter(UUID organizationId, UUID actorUserId, String action, String resourceType, String resourceId,
                         Instant from, Instant to) {
    }

    public record Event(long id, Instant occurredAt, UUID actorUserId, String actorType, UUID organizationId, String action,
                        String resourceType, String resourceId, String ipHash, Map<String, Object> details) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditQueries(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PageResponse<Event> page(Filter f, int page, int size) {
        String where = """
            WHERE (CAST(:org AS uuid) IS NULL OR organization_id = :org)
              AND (CAST(:actor AS uuid) IS NULL OR actor_user_id = :actor)
              AND (CAST(:action AS varchar) IS NULL OR action = :action)
              AND (CAST(:rtype AS varchar) IS NULL OR resource_type = :rtype)
              AND (CAST(:rid AS varchar) IS NULL OR resource_id = :rid)
              AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
              AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < :to)""";
        long total = bind(jdbc.sql("SELECT count(*) FROM audit_event " + where), f).query(Long.class).single();
        List<Event> events = bind(jdbc.sql("SELECT * FROM audit_event " + where + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset"), f)
            .param("limit", size).param("offset", (long) page * size)
            .query((rs, n) -> new Event(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getObject("actor_user_id", UUID.class), rs.getString("actor_type"), rs.getObject("organization_id", UUID.class),
                rs.getString("action"), rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("ip_hash"),
                details(rs.getString("details"))))
            .list();
        return new PageResponse<>(events, page, size, total, (int) Math.ceilDiv(total, size));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> details(String raw) {
        return raw == null ? Map.of() : json.readValue(raw, Map.class);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Filter f) {
        return spec.param("org", f.organizationId()).param("actor", f.actorUserId()).param("action", f.action())
            .param("rtype", f.resourceType()).param("rid", f.resourceId())
            .param("from", f.from() == null ? null : Timestamp.from(f.from()))
            .param("to", f.to() == null ? null : Timestamp.from(f.to()));
    }
}
