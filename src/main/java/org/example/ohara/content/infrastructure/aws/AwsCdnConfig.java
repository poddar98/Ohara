package org.example.ohara.content.infrastructure.aws;

import org.example.ohara.content.application.CdnSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * CloudFront signed cookies when {@code ohara.cdn.provider=cloudfront}. The signing key is a PKCS#8 PEM ("BEGIN PRIVATE
 * KEY") read from {@code private-key-path} (a file mounted from Secrets Manager) or {@code private-key} (the PEM text,
 * e.g. injected from a secret into the environment). It is loaded at startup, so a missing or malformed key fails fast.
 * Convert an OpenSSL RSA key with {@code openssl pkcs8 -topk8 -nocrypt}.
 */
@Configuration
@ConditionalOnProperty(name = "ohara.cdn.provider", havingValue = "cloudfront")
class AwsCdnConfig {

    @Bean
    CdnSigner cdnSigner(@Value("${ohara.cdn.cloudfront.domain}") String domain,
                        @Value("${ohara.cdn.cloudfront.key-pair-id}") String keyPairId,
                        @Value("${ohara.cdn.cloudfront.private-key-path:}") String keyPath,
                        @Value("${ohara.cdn.cloudfront.private-key:}") String keyPem,
                        @Value("${ohara.cdn.cookie-domain:}") String cookieDomain) throws IOException {
        String pem = !keyPem.isBlank() ? keyPem : Files.readString(Path.of(keyPath));
        return new CloudFrontCdnSigner(domain, keyPairId, privateKey(pem), cookieDomain);
    }

    static PrivateKey privateKey(String pem) {
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException("CloudFront signing key is PKCS#1; convert it with: openssl pkcs8 -topk8 -nocrypt");
        }
        String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            throw new IllegalStateException("CloudFront signing key is not a valid PKCS#8 RSA private key", e);
        }
    }
}
