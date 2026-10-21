package org.example.ohara.auth.application;

import org.example.ohara.auth.api.dto.LoginRequest;
import org.example.ohara.auth.api.dto.RegisterRequest;
import org.example.ohara.auth.domain.AuthIdentity;
import org.example.ohara.auth.domain.PasswordCredential;
import org.example.ohara.auth.domain.RefreshToken;
import org.example.ohara.auth.infrastructure.jwt.JwtTokenProvider;
import org.example.ohara.auth.infrastructure.persistence.AuthIdentityRepository;
import org.example.ohara.auth.infrastructure.persistence.PasswordCredentialRepository;
import org.example.ohara.auth.infrastructure.persistence.RefreshTokenRepository;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserRole;
import org.example.ohara.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private AccountRecoveryService accountRecoveryService;
    private AuthEmails emails;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        authIdentityRepository = mock(AuthIdentityRepository.class);
        passwordCredentialRepository = mock(PasswordCredentialRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authenticationManager = mock(AuthenticationManager.class);
        accountRecoveryService = mock(AccountRecoveryService.class);
        emails = mock(AuthEmails.class);
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", "ohara-local-jwt-secret-key-should-be-at-least-32-chars");
        ReflectionTestUtils.setField(jwtTokenProvider, "accessTokenValidityMs", 900000L);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshTokenValidityMs", 604800000L);
        jwtTokenProvider.init();

        authService = new AuthService(
            userService,
            authIdentityRepository,
            passwordCredentialRepository,
            refreshTokenRepository,
            passwordEncoder,
            authenticationManager,
            jwtTokenProvider,
            new TokenIssuer(jwtTokenProvider, refreshTokenRepository),
            accountRecoveryService,
            emails
        );
    }

    private User existingUser(UserStatus status) {
        User user = new User("ada@example.com", "Ada", "Lovelace");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setStatus(status);
        UserRole role = new UserRole("ROLE_USER");
        role.setUser(user);
        user.getRoles().add(role);
        return user;
    }

    private RefreshToken stored(User user, String rawToken) {
        return new RefreshToken(user, SecretTokens.sha256(rawToken), jwtTokenProvider.getExpiration(rawToken));
    }

    @Test
    void registerCreatesPendingUserWithCredentialAndSendsVerification() {
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret1234")).thenReturn("encoded-password");
        when(userService.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(authIdentityRepository.save(any(AuthIdentity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.register(new RegisterRequest("Ada", "Lovelace", " User@Example.com ", "secret1234"));

        verify(userService).save(org.mockito.ArgumentMatchers.argThat(user ->
            user.getEmail().equals("user@example.com") && user.getStatus() == UserStatus.PENDING_VERIFICATION));
        verify(passwordCredentialRepository).save(any(PasswordCredential.class));
        verify(accountRecoveryService).sendVerification(any(User.class), any(Instant.class));
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void registeringAVerifiedEmailSendsANoticeAndCreatesNothing() {
        when(userService.findByEmail("ada@example.com")).thenReturn(Optional.of(existingUser(UserStatus.ACTIVE)));

        authService.register(new RegisterRequest("Ada", "Lovelace", "ada@example.com", "secret1234"));

        verify(emails).accountAlreadyExists("ada@example.com");
        verify(userService, never()).save(any());
        verifyNoInteractions(passwordCredentialRepository);
    }

    @Test
    void registeringAnUnverifiedEmailResendsVerification() {
        when(userService.findByEmail("ada@example.com")).thenReturn(Optional.of(existingUser(UserStatus.PENDING_VERIFICATION)));

        authService.register(new RegisterRequest("Ada", "Lovelace", "ada@example.com", "secret1234"));

        verify(accountRecoveryService).resendVerification("ada@example.com");
        verify(userService, never()).save(any());
    }

    @Test
    void failedLoginIsCountedAndSuccessfulLoginResetsTheCounter() {
        User user = existingUser(UserStatus.ACTIVE);
        PasswordCredential credential = new PasswordCredential(new AuthIdentity(user, "local", "ada@example.com"), "hash");
        when(passwordCredentialRepository.findByAuthIdentityProviderAndAuthIdentityIdentifier("local", "ada@example.com"))
            .thenReturn(Optional.of(credential));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("ada@example.com", "wrong-pass")))
            .isInstanceOf(BadCredentialsException.class);
        assertThat(credential.getFailedAttempts()).isEqualTo(1);

        org.mockito.Mockito.reset(authenticationManager);
        when(userService.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IssuedTokens tokens = authService.login(new LoginRequest("Ada@Example.com", "secret1234"));

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(credential.getFailedAttempts()).isZero();
        assertThat(user.getLastLoginAt()).isNotNull();
    }

    @Test
    void fifthConsecutiveFailureLocksTheCredential() {
        User user = existingUser(UserStatus.ACTIVE);
        PasswordCredential credential = new PasswordCredential(new AuthIdentity(user, "local", "ada@example.com"), "hash");
        Instant now = Instant.now();

        for (int i = 0; i < 4; i++) {
            credential.recordFailedLogin(now);
        }
        assertThat(credential.isLocked(now)).isFalse();

        credential.recordFailedLogin(now);
        assertThat(credential.isLocked(now)).isTrue();
        assertThat(credential.getLockedUntil()).isEqualTo(now.plus(15, ChronoUnit.MINUTES));
    }

    @Test
    void refreshRevokesPresentedTokenAndIssuesNewPair() {
        User user = existingUser(UserStatus.ACTIVE);
        String oldRefresh = jwtTokenProvider.generateRefreshToken(user);
        RefreshToken stored = stored(user, oldRefresh);
        when(refreshTokenRepository.findByTokenHash(SecretTokens.sha256(oldRefresh))).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IssuedTokens tokens = authService.refresh(oldRefresh);

        assertThat(stored.isRevoked()).isTrue();
        assertThat(tokens.refreshToken()).isNotEqualTo(oldRefresh);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void reusingRevokedRefreshTokenEndsAllSessions() {
        User user = existingUser(UserStatus.ACTIVE);
        String token = jwtTokenProvider.generateRefreshToken(user);
        RefreshToken revoked = stored(user, token);
        revoked.revoke(Instant.now().minus(1, ChronoUnit.MINUTES));
        RefreshToken otherSession = new RefreshToken(user, "other-session-hash", jwtTokenProvider.getExpiration(token));
        when(refreshTokenRepository.findByTokenHash(SecretTokens.sha256(token))).thenReturn(Optional.of(revoked));
        when(refreshTokenRepository.findByUserAndRevokedAtIsNull(user)).thenReturn(List.of(otherSession));

        assertThatThrownBy(() -> authService.refresh(token))
            .isInstanceOf(ApiException.Unauthenticated.class)
            .hasFieldOrPropertyWithValue("code", "refresh_token_reused");
        assertThat(otherSession.isRevoked()).isTrue();
        assertThat(user.getAuthVersion()).isEqualTo(1);
    }

    @Test
    void refreshRejectsTokenThatIsNotInStore() {
        String token = jwtTokenProvider.generateRefreshToken(existingUser(UserStatus.ACTIVE));
        when(refreshTokenRepository.findByTokenHash(SecretTokens.sha256(token))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(token))
            .isInstanceOf(ApiException.Unauthenticated.class)
            .hasFieldOrPropertyWithValue("code", "refresh_token_invalid");
    }

    @Test
    void refreshRejectsAccessToken() {
        String accessToken = jwtTokenProvider.generateAccessToken(existingUser(UserStatus.ACTIVE));

        assertThatThrownBy(() -> authService.refresh(accessToken))
            .isInstanceOf(ApiException.Unauthenticated.class)
            .hasFieldOrPropertyWithValue("code", "refresh_token_invalid");
    }

    @Test
    void refreshIsRejectedAndTokenRevokedWhenAccountIsSuspended() {
        User user = existingUser(UserStatus.SUSPENDED);
        String token = jwtTokenProvider.generateRefreshToken(user);
        RefreshToken stored = stored(user, token);
        when(refreshTokenRepository.findByTokenHash(SecretTokens.sha256(token))).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> authService.refresh(token))
            .isInstanceOf(ApiException.Unauthenticated.class)
            .hasFieldOrPropertyWithValue("code", "account_inactive");
        assertThat(stored.isRevoked()).isTrue();
    }

    @Test
    void logoutRevokesActiveRefreshToken() {
        User user = existingUser(UserStatus.ACTIVE);
        String token = jwtTokenProvider.generateRefreshToken(user);
        RefreshToken stored = stored(user, token);
        when(refreshTokenRepository.findByTokenHash(SecretTokens.sha256(token))).thenReturn(Optional.of(stored));

        authService.logout(token);

        assertThat(stored.isRevoked()).isTrue();
    }

    @Test
    void logoutIgnoresInvalidToken() {
        authService.logout("not-a-jwt");

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void logoutAllRevokesEverySessionAndBumpsAuthVersion() {
        User user = existingUser(UserStatus.ACTIVE);
        RefreshToken session = new RefreshToken(user, "hash", Instant.now().plus(1, ChronoUnit.DAYS));
        when(userService.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findByUserAndRevokedAtIsNull(eq(user))).thenReturn(List.of(session));

        authService.logoutAll(user.getId());

        assertThat(session.isRevoked()).isTrue();
        assertThat(user.getAuthVersion()).isEqualTo(1);
    }
}
