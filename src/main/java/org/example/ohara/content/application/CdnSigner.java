package org.example.ohara.content.application;

import java.time.Instant;
import java.util.List;

/**
 * Delivery authorization port, shaped like CloudFront signed cookies: one set of cookies grants read access to every
 * file under a path prefix (an HLS master, its variants and segments) until it expires.
 */
public interface CdnSigner {

    record Cookie(String name, String value, String domain, String path, long maxAgeSeconds, boolean secure,
                  boolean httpOnly, String sameSite) {
    }

    /** Public URL of an object key on the CDN. */
    String urlOf(String objectKey);

    /** Cookies granting access to every key starting with {@code keyPrefix} until {@code expiresAt}. */
    List<Cookie> sign(String keyPrefix, Instant expiresAt);
}
