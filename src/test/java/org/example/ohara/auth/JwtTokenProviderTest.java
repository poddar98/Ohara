package org.example.ohara.auth;

import org.example.ohara.config.JwtTokenProvider;
import org.example.ohara.user.User;
import org.example.ohara.user_role.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", "ohara-local-jwt-secret-key-should-be-at-least-32-chars");
        ReflectionTestUtils.setField(jwtTokenProvider, "accessTokenValidityMs", 3600000L);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshTokenValidityMs", 604800000L);
        jwtTokenProvider.init();
    }

    @Test
    void generatesAndValidatesToken() {
        User user = new User("ada@example.com", "Ada", "Lovelace");
        UserRole role = new UserRole("ROLE_USER");
        role.setUser(user);
        user.getRoles().add(role);

        String token = jwtTokenProvider.generateAccessToken(user);

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUsernameFromToken(token)).isEqualTo("ada@example.com");
    }
}
