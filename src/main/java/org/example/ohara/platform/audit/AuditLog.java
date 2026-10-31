package org.example.ohara.platform.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Records audit events. {@link #record} joins the caller's transaction, so an action and its audit row commit or roll
 * back together. {@link #recordIndependently} commits on its own, for events about a request that is being refused
 * (e.g. cross-tenant access), whose own transaction rolls back or is read-only.
 * <p>
 * The client IP is stored only as a keyed hash ({@code ohara.audit.ip-hash-key}): enough to correlate events from one
 * address, not to recover it. Details must not contain secrets or personal data beyond ids.
 */
@Component
public class AuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final byte[] ipKey;

    AuditLog(JdbcClient jdbc, JsonMapper json, @Value("${ohara.audit.ip-hash-key:dev-only-audit-ip-key}") String ipKey) {
        this.jdbc = jdbc;
        this.json = json;
        this.ipKey = ipKey.getBytes(StandardCharsets.UTF_8);
    }

    /** An action by a signed-in user (or by the system when {@code actorUserId} is null). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(UUID actorUserId, UUID organizationId, String action, String resourceType, Object resourceId,
                       Map<String, ?> details) {
        insert(actorUserId, organizationId, action, resourceType, resourceId, details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(UUID actorUserId, UUID organizationId, String action, String resourceType, Object resourceId,
                                    Map<String, ?> details) {
        insert(actorUserId, organizationId, action, resourceType, resourceId, details);
    }

    /** Details from alternating names and values; null values are kept (Map.of rejects them). */
    public static Map<String, Object> details(Object... namesAndValues) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            map.put(String.valueOf(namesAndValues[i]), namesAndValues[i + 1]);
        }
        return map;
    }

    private void insert(UUID actorUserId, UUID organizationId, String action, String resourceType, Object resourceId,
                        Map<String, ?> details) {
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
            ? attrs.getRequest() : null;
        String agent = request == null ? null : request.getHeader("User-Agent");
        jdbc.sql("""
                INSERT INTO audit_event (actor_user_id, actor_type, organization_id, action, resource_type, resource_id,
                       ip_hash, user_agent, details)
                VALUES (:actor, :type, :org, :action, :rtype, :rid, :ip, :agent, CAST(:details AS jsonb))""")
            .param("actor", actorUserId).param("type", actorUserId == null ? "SYSTEM" : "USER").param("org", organizationId)
            .param("action", action).param("rtype", resourceType)
            .param("rid", resourceId == null ? null : resourceId.toString())
            .param("ip", request == null ? null : ipHash(request.getRemoteAddr()))
            .param("agent", agent == null ? null : agent.length() > 256 ? agent.substring(0, 256) : agent)
            .param("details", json.writeValueAsString(details == null ? Map.of() : details))
            .update();
    }

    private String ipHash(String ip) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ipKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(ip.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
