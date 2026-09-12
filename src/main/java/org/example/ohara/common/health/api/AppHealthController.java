package org.example.ohara.common.health.api;

import org.example.ohara.common.health.application.AppHealthService;
import org.example.ohara.common.health.domain.ApplicationStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AppHealthController {

    private final AppHealthService appHealthService;

    public AppHealthController(AppHealthService appHealthService) {
        this.appHealthService = appHealthService;
    }

    @GetMapping("/health")
    public ApplicationStatus health() {
        return appHealthService.getStatus();
    }
}
