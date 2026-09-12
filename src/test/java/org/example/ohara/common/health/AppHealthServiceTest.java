package org.example.ohara.common.health;

import org.example.ohara.common.health.application.AppHealthService;
import org.example.ohara.common.health.domain.ApplicationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppHealthServiceTest {

    @Test
    void returnsUpStatusWithServiceNameAndVersion() {
        AppHealthService service = new AppHealthService("ohara-api", "1.2.3");

        ApplicationStatus status = service.getStatus();

        assertThat(status).isNotNull();
        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.service()).isEqualTo("ohara-api");
        assertThat(status.version()).isEqualTo("1.2.3");
    }
}
