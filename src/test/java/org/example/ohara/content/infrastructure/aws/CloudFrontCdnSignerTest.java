package org.example.ohara.content.infrastructure.aws;

import org.example.ohara.content.application.CdnSigner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks the cookies the way CloudFront does: decode the policy, verify its signature with the key group's public key. */
class CloudFrontCdnSignerTest {

    static KeyPair keys;
    static CloudFrontCdnSigner signer;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(keys.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        signer = new CloudFrontCdnSigner("media.example.in", "K2JCJMDEHXQW5F", AwsCdnConfig.privateKey(pem), ".example.in");
    }

    /** CloudFront's URL-safe base64: + → -, = → _, / → ~. */
    private static byte[] cloudFrontDecode(String value) {
        return Base64.getDecoder().decode(value.replace('-', '+').replace('_', '=').replace('~', '/'));
    }

    @Test
    void cookiesGrantOneVideoPrefixUntilTheExpiryAndCarryAValidSignature() throws Exception {
        String prefix = "delivery/org/o1/asset/a1/g2/hls/";
        Instant expires = Instant.now().plus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

        List<CdnSigner.Cookie> cookies = signer.sign(prefix, expires);
        Map<String, CdnSigner.Cookie> byName = cookies.stream().collect(Collectors.toMap(CdnSigner.Cookie::name, c -> c));

        assertThat(byName).containsKeys("CloudFront-Policy", "CloudFront-Signature", "CloudFront-Key-Pair-Id");
        assertThat(byName.get("CloudFront-Key-Pair-Id").value()).isEqualTo("K2JCJMDEHXQW5F");
        assertThat(cookies).allSatisfy(c -> {
            assertThat(c.path()).isEqualTo("/" + prefix);
            assertThat(c.domain()).isEqualTo(".example.in");
            assertThat(c.secure()).isTrue();
            assertThat(c.httpOnly()).isTrue();
            assertThat(c.maxAgeSeconds()).isBetween(1190L, 1200L);
        });

        byte[] policy = cloudFrontDecode(byName.get("CloudFront-Policy").value());
        String json = new String(policy, StandardCharsets.UTF_8).replace(" ", "");
        assertThat(json).contains("\"Resource\":\"https://media.example.in/" + prefix + "*\"");
        assertThat(json).contains("\"AWS:EpochTime\":" + expires.getEpochSecond());

        Signature verify = Signature.getInstance("SHA1withRSA");
        verify.initVerify(keys.getPublic());
        verify.update(policy);
        assertThat(verify.verify(cloudFrontDecode(byName.get("CloudFront-Signature").value()))).isTrue();
    }

    @Test
    void urlsPointAtTheMediaDomainAndBadKeysFailFast() {
        assertThat(signer.urlOf("delivery/org/o1/asset/a1/g2/hls/master.m3u8"))
            .isEqualTo("https://media.example.in/delivery/org/o1/asset/a1/g2/hls/master.m3u8");
        assertThatThrownBy(() -> AwsCdnConfig.privateKey("-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"))
            .hasMessageContaining("openssl pkcs8");
        assertThatThrownBy(() -> AwsCdnConfig.privateKey("-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----"))
            .hasMessageContaining("not a valid PKCS#8");
    }
}
