package org.example.ohara;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ApplicationBeansTests {

    @Autowired
    ApplicationContext context;

    @Test
    void healthEndpointBeanPresent() {
        // avoid importing actuator classes directly; check bean by conventional name
        assertThat(context.containsBean("healthEndpoint")).isTrue();
    }

    @Test
    void userDetailsServiceBeanPresent() {
        UserDetailsService uds = context.getBean(UserDetailsService.class);
        assertThat(uds).isNotNull();
    }
}
