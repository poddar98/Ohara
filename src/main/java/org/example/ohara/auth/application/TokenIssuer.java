package org.example.ohara.auth.application;

import org.example.ohara.auth.domain.RefreshToken;
import org.example.ohara.auth.infrastructure.jwt.JwtTokenProvider;
import org.example.ohara.auth.infrastructure.persistence.RefreshTokenRepository;
import org.example.ohara.user.domain.User;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Issues an access/refresh pair and persists the refresh token's hash. Callers own the transaction. */
@Component
public class TokenIssuer {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    public TokenIssuer(JwtTokenProvider jwtTokenProvider, RefreshTokenRepository refreshTokenRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public IssuedTokens issue(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);
        Instant refreshExpiresAt = jwtTokenProvider.getExpiration(refreshToken);

        refreshTokenRepository.save(new RefreshToken(user, SecretTokens.sha256(refreshToken), refreshExpiresAt));

        return new IssuedTokens(accessToken, refreshToken, refreshExpiresAt, user.getEmail());
    }

    /** Ends every session of the user: refresh tokens revoked, outstanding access tokens rejected. */
    public void revokeAllSessions(User user, Instant now) {
        refreshTokenRepository.findByUserAndRevokedAtIsNull(user).forEach(token -> token.revoke(now));
        user.bumpAuthVersion();
    }
}
