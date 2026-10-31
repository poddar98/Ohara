package org.example.ohara.content.infrastructure.aws;

import org.example.ohara.content.application.CdnSigner;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link CdnSigner} for CloudFront signed cookies (docs 06 §6). A custom policy grants the files under one key prefix
 * (one video generation: master, variants, segments, captions) until the expiry, signed with a private key whose
 * public half is in the distribution's trusted key group.
 * <p>
 * The cookies are issued by the API and must reach the media host: both share {@code cookieDomain} (e.g. API at
 * api.example.in, media at media.example.in, cookie domain .example.in). Each cookie's Path is the video's prefix, so
 * cookies for different videos sit side by side instead of overwriting each other.
 */
public class CloudFrontCdnSigner implements CdnSigner {

    private final CloudFrontUtilities cloudFront = CloudFrontUtilities.create();
    private final String domain;
    private final String keyPairId;
    private final PrivateKey privateKey;
    private final String cookieDomain;

    public CloudFrontCdnSigner(String domain, String keyPairId, PrivateKey privateKey, String cookieDomain) {
        this.domain = domain;
        this.keyPairId = keyPairId;
        this.privateKey = privateKey;
        this.cookieDomain = cookieDomain == null || cookieDomain.isBlank() ? null : cookieDomain;
    }

    @Override
    public String urlOf(String objectKey) {
        return "https://" + domain + "/" + objectKey;
    }

    @Override
    public List<Cookie> sign(String keyPrefix, Instant expiresAt) {
        String prefixUrl = urlOf(keyPrefix);
        CookiesForCustomPolicy signed = cloudFront.getCookiesForCustomPolicy(r -> r
            .resourceUrl(prefixUrl)
            .resourceUrlPattern(prefixUrl + "*")
            .keyPairId(keyPairId)
            .privateKey(privateKey)
            .expirationDate(expiresAt));
        long maxAge = Math.max(0, expiresAt.getEpochSecond() - Instant.now().getEpochSecond());
        String path = "/" + keyPrefix;
        List<Cookie> cookies = new ArrayList<>();
        for (String header : new String[] {signed.policyHeaderValue(), signed.signatureHeaderValue(), signed.keyPairIdHeaderValue(),
                signed.hashAlgorithmHeaderValue()}) {
            if (header == null || header.isBlank()) {
                continue;
            }
            int eq = header.indexOf('=');
            cookies.add(new Cookie(header.substring(0, eq), header.substring(eq + 1), cookieDomain, path, maxAge, true, true, "Lax"));
        }
        return cookies;
    }
}
