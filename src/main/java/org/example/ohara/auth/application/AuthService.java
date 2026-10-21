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
import org.example.ohara.user.domain.UserProfile;
import org.example.ohara.user.domain.UserRole;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;

@Service
public class AuthService {

    private final UserService userService;
    private final AuthIdentityRepository authIdentityRepository;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(
            UserService userService,
            AuthIdentityRepository authIdentityRepository,
            PasswordCredentialRepository passwordCredentialRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtTokenProvider jwtTokenProvider) {
        this.userService = userService;
        this.authIdentityRepository = authIdentityRepository;
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public IssuedTokens register(RegisterRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        if (userService.existsByEmail(email)) {
            throw new ApiException.Conflict("email_already_registered", "An account with this email already exists");
        }

        User user = new User(email, request.firstName(), request.lastName());
        user.setRoles(new ArrayList<>());

        UserProfile profile = new UserProfile(user, request.firstName(), request.lastName());
        user.setProfile(profile);

        UserRole userRole = new UserRole("ROLE_USER");
        userRole.setUser(user);
        user.getRoles().add(userRole);

        User saved = userService.save(user);

        AuthIdentity authIdentity = authIdentityRepository.save(new AuthIdentity(saved, AuthIdentity.LOCAL_PROVIDER, email));
        String encodedPassword = passwordEncoder.encode(request.password());
        passwordCredentialRepository.save(new PasswordCredential(authIdentity, encodedPassword));

        return issueTokens(saved);
    }

    @Transactional
    public IssuedTokens login(LoginRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(email, request.password())
        );

        if (!authentication.isAuthenticated()) {
            throw new ApiException.Unauthenticated("invalid_credentials", "Invalid credentials");
        }

        User user = userService.findByEmail(email)
            .orElseThrow(() -> new ApiException.Unauthenticated("invalid_credentials", "Invalid credentials"));

        user.setLastLoginAt(Instant.now());

        return issueTokens(user);
    }

    /**
     * Rotates the refresh token: the presented token is revoked and a new pair is issued.
     * Presenting an already-revoked token is treated as reuse: every active session of that user is revoked and the
     * user's auth_version is bumped, so outstanding access tokens stop working too.
     */
    // noRollbackFor: reuse detection revokes sessions and then throws; the revocations must survive the exception.
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedTokens refresh(String rawRefreshToken) {
        if (!jwtTokenProvider.validateRefreshToken(rawRefreshToken)) {
            throw invalidRefreshToken();
        }

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
            .orElseThrow(AuthService::invalidRefreshToken);

        Instant now = Instant.now();
        User user = stored.getUser();
        if (stored.isRevoked()) {
            refreshTokenRepository.findByUserAndRevokedAtIsNull(user).forEach(token -> token.revoke(now));
            user.bumpAuthVersion();
            throw new ApiException.Unauthenticated("refresh_token_reused", "Refresh token has been revoked");
        }
        if (stored.isExpired(now)) {
            throw new ApiException.Unauthenticated("refresh_token_expired", "Refresh token has expired");
        }
        if (!user.isActive()) {
            stored.revoke(now);
            throw new ApiException.Unauthenticated("account_inactive", "Account is not active");
        }

        stored.revoke(now);
        return issueTokens(user);
    }

    /** Idempotent: an unknown, invalid or already-revoked token is a no-op. */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (!jwtTokenProvider.validateRefreshToken(rawRefreshToken)) {
            return;
        }

        refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
            .filter(token -> !token.isRevoked())
            .ifPresent(token -> token.revoke(Instant.now()));
    }

    private IssuedTokens issueTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);
        Instant refreshExpiresAt = jwtTokenProvider.getExpiration(refreshToken);

        refreshTokenRepository.save(new RefreshToken(user, hashToken(refreshToken), refreshExpiresAt));

        return new IssuedTokens(accessToken, refreshToken, refreshExpiresAt, user.getEmail());
    }

    private static ApiException invalidRefreshToken() {
        return new ApiException.Unauthenticated("refresh_token_invalid", "Invalid refresh token");
    }

    String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash refresh token", ex);
        }
    }
}
