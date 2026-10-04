package org.example.ohara.auth.application;

import org.example.ohara.auth.api.dto.RegisterRequest;
import org.example.ohara.auth.domain.AuthIdentity;
import org.example.ohara.auth.domain.RefreshToken;
import org.example.ohara.auth.infrastructure.jwt.JwtTokenProvider;
import org.example.ohara.auth.infrastructure.persistence.AuthIdentityRepository;
import org.example.ohara.auth.infrastructure.persistence.PasswordCredentialRepository;
import org.example.ohara.auth.infrastructure.persistence.RefreshTokenRepository;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private UserService userService;
    private AuthIdentityRepository authIdentityRepository;
    private PasswordCredentialRepository passwordCredentialRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private PasswordEncoder passwordEncoder;
    private AuthenticationManager authenticationManager;
    private JwtTokenProvider jwtTokenProvider;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        authIdentityRepository = mock(AuthIdentityRepository.class);
        passwordCredentialRepository = mock(PasswordCredentialRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authenticationManager = mock(AuthenticationManager.class);
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", "ohara-local-jwt-secret-key-should-be-at-least-32-chars");
        ReflectionTestUtils.setField(jwtTokenProvider, "accessTokenValidityMs", 3600000L);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshTokenValidityMs", 604800000L);
        jwtTokenProvider.init();

        authService = new AuthService(
            userService,
            authIdentityRepository,
            passwordCredentialRepository,
            refreshTokenRepository,
            passwordEncoder,
            authenticationManager,
            jwtTokenProvider
        );
    }

    private User existingUser() {
        User user = new User("ada@example.com", "Ada", "Lovelace");
        UserRole role = new UserRole("ROLE_USER");
        role.setUser(user);
        user.getRoles().add(role);
        return user;
    }

    @Test
    void registerCreatesUserIdentityAndPersistsRefreshToken() {
        when(userService.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret1234")).thenReturn("encoded-password");
        when(userService.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(authIdentityRepository.save(any(AuthIdentity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IssuedTokens tokens = authService.register(new RegisterRequest("Ada", "Lovelace", "user@example.com", "secret1234"));

        assertThat(tokens.email()).isEqualTo("user@example.com");
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.refreshExpiresAt()).isAfter(LocalDateTime.now());
        verify(authIdentityRepository).save(any(AuthIdentity.class));
        verify(passwordCredentialRepository).save(any());
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void refreshRevokesPresentedTokenAndIssuesNewPair() {
        User user = existingUser();
        String oldRefresh = jwtTokenProvider.generateRefreshToken(user);
        RefreshToken stored = new RefreshToken(user, authService.hashToken(oldRefresh), jwtTokenProvider.getExpiration(oldRefresh));
        when(refreshTokenRepository.findByTokenHash(authService.hashToken(oldRefresh))).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IssuedTokens tokens = authService.refresh(oldRefresh);

        assertThat(stored.isRevoked()).isTrue();
        assertThat(tokens.refreshToken()).isNotEqualTo(oldRefresh);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void reusingRevokedRefreshTokenRevokesAllActiveSessions() {
        User user = existingUser();
        String token = jwtTokenProvider.generateRefreshToken(user);
        RefreshToken revoked = new RefreshToken(user, authService.hashToken(token), jwtTokenProvider.getExpiration(token));
        revoked.revoke(LocalDateTime.now().minusMinutes(1));
        RefreshToken otherSession = new RefreshToken(user, "other-session-hash", jwtTokenProvider.getExpiration(token));
        when(refreshTokenRepository.findByTokenHash(authService.hashToken(token))).thenReturn(Optional.of(revoked));
        when(refreshTokenRepository.findByUserAndRevokedAtIsNull(user)).thenReturn(List.of(otherSession));

        assertThatThrownBy(() -> authService.refresh(token))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Refresh token has been revoked");
        assertThat(otherSession.isRevoked()).isTrue();
    }

    @Test
    void refreshRejectsTokenThatIsNotInStore() {
        String token = jwtTokenProvider.generateRefreshToken(existingUser());
        when(refreshTokenRepository.findByTokenHash(authService.hashToken(token))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(token))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid refresh token");
    }

    @Test
    void refreshRejectsAccessToken() {
        String accessToken = jwtTokenProvider.generateAccessToken(existingUser());

        assertThatThrownBy(() -> authService.refresh(accessToken))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid refresh token");
    }

    @Test
    void logoutRevokesActiveRefreshToken() {
        User user = existingUser();
        String token = jwtTokenProvider.generateRefreshToken(user);
        RefreshToken stored = new RefreshToken(user, authService.hashToken(token), jwtTokenProvider.getExpiration(token));
        when(refreshTokenRepository.findByTokenHash(authService.hashToken(token))).thenReturn(Optional.of(stored));

        authService.logout(token);

        assertThat(stored.isRevoked()).isTrue();
    }

    @Test
    void logoutIgnoresInvalidToken() {
        authService.logout("not-a-jwt");

        verifyNoInteractions(refreshTokenRepository);
    }
}
