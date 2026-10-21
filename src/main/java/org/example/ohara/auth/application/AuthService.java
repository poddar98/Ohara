package org.example.ohara.auth.application;

import org.example.ohara.auth.api.dto.LoginRequest;
import org.example.ohara.auth.api.dto.RegisterRequest;
import org.example.ohara.auth.domain.AuthIdentity;
import org.example.ohara.auth.domain.PasswordCredential;
import org.example.ohara.auth.domain.RefreshToken;
import org.example.ohara.auth.infrastructure.jwt.JwtTokenProvider;
import org.example.ohara.auth.infrastructure.persistence.PasswordCredentialRepository;
import org.example.ohara.auth.infrastructure.persistence.RefreshTokenRepository;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UserService userService;
    private final AccountProvisioning accountProvisioning;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenIssuer tokenIssuer;
    private final AccountRecoveryService accountRecoveryService;
    private final AuthEmails emails;

    public AuthService(
            UserService userService,
            AccountProvisioning accountProvisioning,
            PasswordCredentialRepository passwordCredentialRepository,
            RefreshTokenRepository refreshTokenRepository,
            AuthenticationManager authenticationManager,
            JwtTokenProvider jwtTokenProvider,
            TokenIssuer tokenIssuer,
            AccountRecoveryService accountRecoveryService,
            AuthEmails emails) {
        this.userService = userService;
        this.accountProvisioning = accountProvisioning;
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenIssuer = tokenIssuer;
        this.accountRecoveryService = accountRecoveryService;
        this.emails = emails;
    }

    /**
     * Same outcome whatever the email's state, so the endpoint can't be used to discover accounts:
     * a new account gets a verification email, an unverified one gets it again, an existing one gets a notice.
     */
    @Transactional
    public void register(RegisterRequest request) {
        String email = AccountRecoveryService.normalize(request.email());
        Optional<User> existing = userService.findByEmail(email);
        if (existing.isPresent()) {
            if (existing.get().getStatus() == UserStatus.PENDING_VERIFICATION) {
                accountRecoveryService.resendVerification(email);
            } else {
                emails.accountAlreadyExists(email);
            }
            return;
        }

        User created = accountProvisioning.createPendingAccount(email, request.firstName(), request.lastName(), request.password());
        accountRecoveryService.sendVerification(created, Instant.now());
    }

    /** Unverified accounts may sign in; their tokens are limited until the email is verified. */
    // noRollbackFor: a failed attempt is counted and must be persisted even though the login fails.
    @Transactional(noRollbackFor = AuthenticationException.class)
    public IssuedTokens login(LoginRequest request) {
        String email = AccountRecoveryService.normalize(request.email());
        Instant now = Instant.now();
        Optional<PasswordCredential> credential =
            passwordCredentialRepository.findByAuthIdentityProviderAndAuthIdentityIdentifier(AuthIdentity.LOCAL_PROVIDER, email);

        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (BadCredentialsException ex) {
            credential.ifPresent(c -> c.recordFailedLogin(now));
            throw ex;
        }

        User user = userService.findByEmail(email)
            .orElseThrow(() -> new ApiException.Unauthenticated("invalid_credentials", "Invalid credentials"));
        credential.ifPresent(PasswordCredential::recordSuccessfulLogin);
        user.setLastLoginAt(now);
        return tokenIssuer.issue(user);
    }

    /**
     * Rotates the refresh token: the presented token is revoked and a new pair is issued.
     * Presenting an already-revoked token is treated as reuse: every session of that user is ended
     * (refresh tokens revoked, auth_version bumped so outstanding access tokens stop working).
     */
    // noRollbackFor: reuse detection revokes sessions and then throws; the revocations must survive the exception.
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens refresh(String rawRefreshToken) {
        if (!jwtTokenProvider.validateRefreshToken(rawRefreshToken)) {
            throw invalidRefreshToken();
        }

        RefreshToken stored = refreshTokenRepository.findByTokenHash(SecretTokens.sha256(rawRefreshToken))
            .orElseThrow(AuthService::invalidRefreshToken);

        Instant now = Instant.now();
        User user = stored.getUser();
        if (stored.isRevoked()) {
            tokenIssuer.revokeAllSessions(user, now);
            throw new ApiException.Unauthenticated("refresh_token_reused", "Refresh token has been revoked");
        }
        if (stored.isExpired(now)) {
            throw new ApiException.Unauthenticated("refresh_token_expired", "Refresh token has expired");
        }
        if (!user.canSignIn()) {
            stored.revoke(now);
            throw new ApiException.Unauthenticated("account_inactive", "Account is not active");
        }

        stored.revoke(now);
        return tokenIssuer.issue(user);
    }

    /** Idempotent: an unknown, invalid or already-revoked token is a no-op. */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (!jwtTokenProvider.validateRefreshToken(rawRefreshToken)) {
            return;
        }

        refreshTokenRepository.findByTokenHash(SecretTokens.sha256(rawRefreshToken))
            .filter(token -> !token.isRevoked())
            .ifPresent(token -> token.revoke(Instant.now()));
    }

    @Transactional
    public void logoutAll(UUID userId) {
        userService.findById(userId).ifPresent(user -> tokenIssuer.revokeAllSessions(user, Instant.now()));
    }

    private static ApiException invalidRefreshToken() {
        return new ApiException.Unauthenticated("refresh_token_invalid", "Invalid refresh token");
    }
}
