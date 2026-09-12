package org.example.ohara.common.health.application;

import org.example.ohara.common.health.domain.ApplicationStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AppHealthService {

    private final String serviceName;
    private final String version;

    public AppHealthService(
            @Value("${spring.application.name:Ohara}") String serviceName,
            @Value("${info.app.version:0.0.1-SNAPSHOT}") String version) {
        this.serviceName = serviceName;
        this.version = version;
    }

    public ApplicationStatus getStatus() {
        return new ApplicationStatus("UP", serviceName, version);
    }
}
