package org.example.ohara.auth.infrastructure.jwt;

import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

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

    private User newUser() {
        User user = new User("ada@example.com", "Ada", "Lovelace");
        UserRole role = new UserRole("ROLE_USER");
        role.setUser(user);
        user.getRoles().add(role);
        return user;
    }

    @Test
    void generatesAndValidatesToken() {
        String token = jwtTokenProvider.generateAccessToken(newUser());

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUsernameFromToken(token)).isEqualTo("ada@example.com");
    }

    @Test
    void accessTokenIsNotAcceptedAsRefreshToken() {
        String accessToken = jwtTokenProvider.generateAccessToken(newUser());

        assertThat(jwtTokenProvider.validateAccessToken(accessToken)).isTrue();
        assertThat(jwtTokenProvider.validateRefreshToken(accessToken)).isFalse();
    }

    @Test
    void refreshTokenIsNotAcceptedAsAccessToken() {
        String refreshToken = jwtTokenProvider.generateRefreshToken(newUser());

        assertThat(jwtTokenProvider.validateRefreshToken(refreshToken)).isTrue();
        assertThat(jwtTokenProvider.validateAccessToken(refreshToken)).isFalse();
    }

    @Test
    void tokensIssuedInTheSameSecondAreDistinct() {
        User user = newUser();

        String first = jwtTokenProvider.generateRefreshToken(user);
        String second = jwtTokenProvider.generateRefreshToken(user);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void expirationIsInTheFuture() {
        String refreshToken = jwtTokenProvider.generateRefreshToken(newUser());

        assertThat(jwtTokenProvider.getExpiration(refreshToken)).isAfter(LocalDateTime.now());
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtTokenProvider.generateAccessToken(newUser());

        assertThat(jwtTokenProvider.validateToken(token + "x")).isFalse();
    }
}
