package org.example.ohara.common.health;

import org.example.ohara.common.health.api.AppHealthController;
import org.example.ohara.common.health.application.AppHealthService;
import org.example.ohara.common.health.domain.ApplicationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppHealthControllerTest {

    @Test
    void healthEndpointReturnsApplicationStatus() throws Exception {
        AppHealthService appHealthService = mock(AppHealthService.class);
        when(appHealthService.getStatus())
                .thenReturn(new ApplicationStatus("UP", "ohara-api", "1.2.3"));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AppHealthController(appHealthService)).build();

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("ohara-api"))
                .andExpect(jsonPath("$.version").value("1.2.3"));
    }
}
