package org.example.ohara.content.infrastructure.cdn;

import org.example.ohara.content.application.CdnSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Stand-in for CloudFront signed cookies. The cookie carries an HMAC-signed (key prefix, expiry) policy; the
 * /dev-cdn endpoint serves a file only if a valid, unexpired policy covers its key — the same check CloudFront makes.
 */
@Component
@ConditionalOnProperty(name = "ohara.cdn.provider", havingValue = "local", matchIfMissing = true)
public class LocalCdn implements CdnSigner {

    static final String COOKIE = "OHARA-DEV-CDN";
    static final String PATH = "/dev-cdn/";

    private final String baseUrl;
    private final boolean secure;
    private final byte[] key = new byte[32];

    public LocalCdn(@Value("${ohara.cdn.local.base-url:http://localhost:8080}") String baseUrl,
                    @Value("${ohara.cdn.cookie-secure:false}") boolean secure) {
        this.baseUrl = baseUrl;
        this.secure = secure;
        new SecureRandom().nextBytes(key);
    }

    @Override
    public String urlOf(String objectKey) {
        return baseUrl + PATH + objectKey;
    }

    @Override
    public List<Cookie> sign(String keyPrefix, Instant expiresAt) {
        String payload = keyPrefix + "\n" + expiresAt.getEpochSecond();
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + "." + hmac(payload);
        long maxAge = Math.max(0, expiresAt.getEpochSecond() - Instant.now().getEpochSecond());
        return List.of(new Cookie(COOKIE, value, null, PATH + keyPrefix, maxAge, secure, true, "Lax"));
    }

    /** The signed key prefix, if the cookie is genuine and unexpired. */
    Optional<String> verify(String cookieValue) {
        try {
            int dot = cookieValue.lastIndexOf('.');
            String payload = new String(Base64.getUrlDecoder().decode(cookieValue.substring(0, dot)), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(hmac(payload).getBytes(StandardCharsets.UTF_8),
                    cookieValue.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }
            String[] parts = payload.split("\n");
            return Instant.now().getEpochSecond() <= Long.parseLong(parts[1]) ? Optional.of(parts[0]) : Optional.empty();
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
