package org.example.ohara.auth.infrastructure.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and parses HS256 tokens. Subject is the user id (stable across email changes);
 * {@code ver} carries the user's auth_version so tokens can be invalidated server-side.
 */
@Component
public class JwtTokenProvider {

    private static final String ACCESS = "ACCESS";
    private static final String REFRESH = "REFRESH";
    static final String SCOPE_FULL = "full";
    static final String SCOPE_UNVERIFIED = "unverified";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-validity-ms:900000}")
    private long accessTokenValidityMs;

    @Value("${jwt.refresh-token-validity-ms:604800000}")
    private long refreshTokenValidityMs;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Claims of a valid access token. {@code fullScope} is false until the email is verified. */
    public record AccessClaims(CurrentActor actor, int authVersion, boolean fullScope) {
    }

    public String generateAccessToken(User user) {
        return buildToken(user, accessTokenValidityMs, ACCESS);
    }

    public String generateRefreshToken(User user) {
        return buildToken(user, refreshTokenValidityMs, REFRESH);
    }

    private String buildToken(User user, long validityMs, String tokenType) {
        Date now = new Date();
        List<String> roles = user.getRoles().stream().map(UserRole::getName).toList();

        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(user.getId().toString())
            .claim("email", user.getEmail())
            .claim("tokenType", tokenType)
            .claim("roles", roles)
            .claim("ver", user.getAuthVersion())
            .claim("scope", user.isActive() ? SCOPE_FULL : SCOPE_UNVERIFIED)
            .issuedAt(now)
            .expiration(new Date(now.getTime() + validityMs))
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact();
    }

    /** Empty if the token is malformed, expired, badly signed, or not an access token. */
    public Optional<AccessClaims> parseAccessToken(String token) {
        return parse(token, ACCESS).map(claims -> {
            List<String> roles = ((List<?>) claims.get("roles", List.class)).stream()
                .map(Object::toString)
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .toList();
            CurrentActor actor = new CurrentActor(
                UUID.fromString(claims.getSubject()), claims.get("email", String.class), roles);
            return new AccessClaims(actor, claims.get("ver", Integer.class), SCOPE_FULL.equals(claims.get("scope", String.class)));
        });
    }

    public boolean validateAccessToken(String token) {
        return parse(token, ACCESS).isPresent();
    }

    public boolean validateRefreshToken(String token) {
        return parse(token, REFRESH).isPresent();
    }

    public Instant getExpiration(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    private Optional<Claims> parse(String token, String expectedType) {
        try {
            Claims claims = parseClaims(token);
            return expectedType.equals(claims.get("tokenType", String.class)) ? Optional.of(claims) : Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
