package org.example.ohara.auth.application;

import org.example.ohara.auth.api.dto.AuthResponse;
import org.example.ohara.auth.api.dto.LoginRequest;
import org.example.ohara.auth.api.dto.RefreshTokenRequest;
import org.example.ohara.auth.api.dto.RegisterRequest;
import org.example.ohara.auth.domain.AuthIdentity;
import org.example.ohara.auth.domain.PasswordCredential;
import org.example.ohara.auth.domain.RefreshToken;
import org.example.ohara.auth.infrastructure.jwt.JwtTokenProvider;
import org.example.ohara.auth.infrastructure.persistence.RefreshTokenRepository;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserProfile;
import org.example.ohara.user.domain.UserRole;
import org.example.ohara.user.infrastructure.persistence.UserRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("User already exists");
        }

        User user = new User(email, request.firstName(), request.lastName());
        user.setRoles(new ArrayList<>());

        UserProfile profile = new UserProfile(user, request.firstName(), request.lastName());
        user.setProfile(profile);

        AuthIdentity authIdentity = new AuthIdentity(user, "local", email);
        user.getAuthIdentities().add(authIdentity);

        UserRole userRole = new UserRole("ROLE_USER");
        userRole.setUser(user);
        user.getRoles().add(userRole);

        String encodedPassword = passwordEncoder.encode(request.password());
        PasswordCredential passwordCredential = new PasswordCredential(authIdentity, encodedPassword);
        authIdentity.setPasswordCredential(passwordCredential);

        userRepository.save(user);

        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.email().toLowerCase();
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(email, request.password())
        );

        if (!authentication.isAuthenticated()) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

        user.setLastLoginAt(LocalDateTime.now());

        return issueTokens(user);
    }

    /**
     * Rotates the refresh token: the presented token is revoked and a new pair is issued.
     * Presenting an already-revoked token is treated as reuse, so every active session of that user is revoked.
     */
    // noRollbackFor: reuse detection revokes sessions and then throws; the revocations must survive the exception.
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public AuthResponse refresh(RefreshTokenRequest request) {
        String rawToken = request.refreshToken();
        if (!jwtTokenProvider.validateRefreshToken(rawToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hashToken(rawToken))
            .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        LocalDateTime now = LocalDateTime.now();
        if (stored.isRevoked()) {
            refreshTokenRepository.findByUserAndRevokedAtIsNull(stored.getUser()).forEach(token -> token.revoke(now));
            throw new IllegalArgumentException("Refresh token has been revoked");
        }
        if (stored.isExpired(now)) {
            throw new IllegalArgumentException("Refresh token has expired");
        }

        stored.revoke(now);
        return issueTokens(stored.getUser());
    }

    /** Idempotent: an unknown, invalid or already-revoked token is a no-op. */
    @Transactional
    public void logout(RefreshTokenRequest request) {
        String rawToken = request.refreshToken();
        if (!jwtTokenProvider.validateRefreshToken(rawToken)) {
            return;
        }

        refreshTokenRepository.findByTokenHash(hashToken(rawToken))
            .filter(token -> !token.isRevoked())
            .ifPresent(token -> token.revoke(LocalDateTime.now()));
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);

        refreshTokenRepository.save(new RefreshToken(
            user,
            hashToken(refreshToken),
            jwtTokenProvider.getExpiration(refreshToken)
        ));

        return new AuthResponse(accessToken, refreshToken, "Bearer", user.getEmail());
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
