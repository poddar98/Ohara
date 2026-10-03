package org.example.ohara.auth.infrastructure.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-validity-ms:3600000}")
    private long accessTokenValidityMs;

    @Value("${jwt.refresh-token-validity-ms:604800000}")
    private long refreshTokenValidityMs;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(User user) {
        return buildToken(user, accessTokenValidityMs, "ACCESS");
    }

    public String generateRefreshToken(User user) {
        return buildToken(user, refreshTokenValidityMs, "REFRESH");
    }

    private String buildToken(User user, long validityMs, String tokenType) {
        Date now = new Date();
        List<String> roles = user.getRoles().stream()
            .map(UserRole::getName)
            .toList();

        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(user.getEmail())
            .claim("tokenType", tokenType)
            .claim("roles", roles)
            .issuedAt(now)
            .expiration(new Date(now.getTime() + validityMs))
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact();
    }

    public Authentication getAuthentication(String token) {
        Claims claims = parseClaims(token);
        List<SimpleGrantedAuthority> authorities = ((List<?>) claims.get("roles", List.class))
            .stream()
            .map(Object::toString)
            .map(role -> new SimpleGrantedAuthority(role.startsWith("ROLE_") ? role : "ROLE_" + role))
            .toList();

        return new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities);
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean validateAccessToken(String token) {
        return hasTokenType(token, "ACCESS");
    }

    public boolean validateRefreshToken(String token) {
        return hasTokenType(token, "REFRESH");
    }

    private boolean hasTokenType(String token, String expectedType) {
        try {
            return expectedType.equals(parseClaims(token).get("tokenType", String.class));
        } catch (Exception ex) {
            return false;
        }
    }

    public LocalDateTime getExpiration(String token) {
        Date expiration = parseClaims(token).getExpiration();
        return LocalDateTime.ofInstant(expiration.toInstant(), ZoneId.systemDefault());
    }

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
