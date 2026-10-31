package org.example.ohara.platform.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.example.ohara.platform.jobs.internal.JobQueueMetrics;
import org.example.ohara.support.OrgFixtures;
import org.example.ohara.support.OrgFixtures.Org;
import org.example.ohara.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spring Boot switches metrics export off in tests; this class turns the Prometheus registry back on. */
@SpringBootTest(properties = {"management.defaults.metrics.export.enabled=true", "management.prometheus.metrics.export.enabled=true"})
@AutoConfigureMockMvc
class MetricsIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MeterRegistry meters;
    @Autowired PrometheusMeterRegistry prometheus;
    @Autowired JobQueueMetrics queue;

    TestUsers users;
    OrgFixtures fx;

    @BeforeEach
    void setUp() throws Exception {
        users = new TestUsers(jdbc, passwordEncoder, mockMvc);
        fx = new OrgFixtures(mockMvc, users);
    }

    private double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void metricsAreNotServedOnThePublicPort() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        // Not even a platform admin's token opens it here: only the internal management port serves metrics.
        fx.send(get("/actuator/prometheus"), fx.platformAdmin(), null).andExpect(status().isForbidden());
        fx.send(get("/actuator/metrics"), fx.platformAdmin(), null).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void securityAndBusinessEventsAreCounted() throws Exception {
        Org org = fx.orgWithAdmin();
        Org other = fx.orgWithAdmin();
        double denied = count("ohara.access.denied", "reason", "cross_tenant");
        double badLogins = count("ohara.auth.logins", "outcome", "bad_credentials");

        fx.send(get("/api/orgs/{id}/members", org.id()), other.admin(), null).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"nope-nope\"}".formatted(org.admin().email()))).andExpect(status().isUnauthorized());

        assertThat(count("ohara.access.denied", "reason", "cross_tenant")).isEqualTo(denied + 1);
        assertThat(count("ohara.auth.logins", "outcome", "bad_credentials")).isEqualTo(badLogins + 1);
    }

    @Test
    void prometheusExposesRequestLatencyAndQueueHealth() {
        jdbc.sql("""
                INSERT INTO processing_job (id, job_type, idempotency_key, payload, run_after)
                VALUES (gen_random_uuid(), 'METRICS_PROBE', 'METRICS_PROBE:' || gen_random_uuid(), '{}'::jsonb, now() - interval '5 minutes')""")
            .update();
        queue.refresh();

        String scrape = prometheus.scrape();
        assertThat(scrape).contains("ohara_jobs_queue{").contains("type=\"METRICS_PROBE\"");
        assertThat(scrape).contains("ohara_jobs_oldest_ready_seconds{");
        jdbc.sql("DELETE FROM processing_job WHERE job_type = 'METRICS_PROBE'").update();
    }
}
