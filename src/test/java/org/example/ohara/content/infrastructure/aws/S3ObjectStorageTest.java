package org.example.ohara.content.infrastructure.aws;

import org.example.ohara.content.application.ObjectStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The S3 adapter against LocalStack's S3 (signature validation on): the full upload protocol, delivery, copies. */
@Testcontainers(disabledWithoutDocker = true)
class S3ObjectStorageTest {

    static final int MiB = 1024 * 1024;

    @Container
    static final GenericContainer<?> LOCALSTACK = new GenericContainer<>(DockerImageName.parse("localstack/localstack:4.9"))
        .withEnv("SERVICES", "s3")
        .withEnv("S3_SKIP_SIGNATURE_VALIDATION", "0")
        .withExposedPorts(4566)
        .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566)
            .forResponsePredicate(body -> body.contains("\"s3\": \"available\"") || body.contains("\"s3\": \"running\""))
            .withStartupTimeout(Duration.ofMinutes(2)));

    static S3Client s3;
    static S3ObjectStorage storage;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void buckets() {
        String endpoint = "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566);
        AwsStorageConfig config = new AwsStorageConfig("us-east-1", endpoint, true, "test", "test");
        s3 = config.s3Client();
        s3.createBucket(b -> b.bucket("ohara-source"));
        s3.putBucketVersioning(b -> b.bucket("ohara-source").versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
        s3.createBucket(b -> b.bucket("ohara-delivery"));
        storage = new S3ObjectStorage(s3, config.s3Presigner(), "ohara-source", "ohara-delivery");
    }

    private static HttpResponse<byte[]> putPart(URI url, byte[] body) throws Exception {
        return http.send(HttpRequest.newBuilder(url).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] filled(int size, char c) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) c);
        return bytes;
    }

    @Test
    void aMultipartUploadThroughPresignedUrlsBecomesOneVersionedObject() throws Exception {
        String key = "org/o1/asset/" + UUID.randomUUID() + "/source/lecture.mp4";
        byte[] first = filled(5 * MiB, 'a');
        byte[] last = filled(1000, 'b');

        String uploadId = storage.startMultipart(key, "video/mp4");
        ObjectStorage.PresignedPart p1 = storage.presignPart(key, uploadId, 1, first.length, Duration.ofMinutes(5));
        ObjectStorage.PresignedPart p2 = storage.presignPart(key, uploadId, 2, last.length, Duration.ofMinutes(5));
        HttpResponse<byte[]> r1 = putPart(p1.url(), first);
        HttpResponse<byte[]> r2 = putPart(p2.url(), last);
        assertThat(r1.statusCode()).isEqualTo(200);
        assertThat(r2.statusCode()).isEqualTo(200);

        assertThat(storage.listParts(key, uploadId)).extracting(ObjectStorage.UploadedPart::size).containsExactly((long) first.length, 1000L);
        ObjectStorage.StoredObject stored = storage.completeMultipart(key, uploadId, List.of(
            new ObjectStorage.CompletedPart(1, r1.headers().firstValue("ETag").orElseThrow()),
            new ObjectStorage.CompletedPart(2, r2.headers().firstValue("ETag").orElseThrow())));

        assertThat(stored.size()).isEqualTo(first.length + 1000L);
        assertThat(stored.versionId()).isNotBlank();
        assertThat(stored.sha256()).isNull(); // S3 multipart checksums aren't whole-file
        assertThat(storage.head(key)).isPresent();
        try (InputStream in = storage.open(key)) {
            byte[] read = in.readAllBytes();
            assertThat(read).hasSize(first.length + 1000);
            assertThat(read[read.length - 1]).isEqualTo((byte) 'b');
        }
    }

    @Test
    void aPartUrlOnlyAcceptsItsSignedLength() throws Exception {
        String key = "org/o1/asset/" + UUID.randomUUID() + "/source/big.mp4";
        String uploadId = storage.startMultipart(key, "video/mp4");
        ObjectStorage.PresignedPart part = storage.presignPart(key, uploadId, 1, 10, Duration.ofMinutes(5));

        assertThat(putPart(part.url(), filled(20, 'x')).statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(storage.listParts(key, uploadId)).isEmpty();
        storage.abortMultipart(key, uploadId);
        storage.abortMultipart(key, uploadId); // idempotent
        assertThat(storage.listParts(key, uploadId)).isEmpty();
    }

    @Test
    void completingWithWrongPartsIsAMismatch() throws Exception {
        String key = "org/o1/asset/" + UUID.randomUUID() + "/source/x.mp4";
        String uploadId = storage.startMultipart(key, "video/mp4");
        ObjectStorage.PresignedPart part = storage.presignPart(key, uploadId, 1, 100, Duration.ofMinutes(5));
        assertThat(putPart(part.url(), filled(100, 'z')).statusCode()).isEqualTo(200);

        assertThatThrownBy(() -> storage.completeMultipart(key, uploadId, List.of(new ObjectStorage.CompletedPart(1, "\"not-the-etag\""))))
            .isInstanceOf(ObjectStorage.StorageMismatchException.class);
        assertThatThrownBy(() -> storage.completeMultipart(key, uploadId, List.of(new ObjectStorage.CompletedPart(2, "\"x\""))))
            .isInstanceOf(ObjectStorage.StorageMismatchException.class);
    }

    @Test
    void downloadsCarryTheOriginalFileNameAndExpire() throws Exception {
        String key = "org/o1/asset/" + UUID.randomUUID() + "/source/notes.pdf";
        storage.put(key, "%PDF-1.7 notes".getBytes(StandardCharsets.US_ASCII), "application/pdf");

        URI link = storage.presignGet(key, Duration.ofMinutes(1), "Lecture notes ü.pdf");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(link).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).startsWith("%PDF");
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
            .contains("filename=\"Lecture notes _.pdf\"").contains("filename*=UTF-8''Lecture%20notes%20%C3%BC.pdf");

        // A tampered link is refused.
        URI tampered = URI.create(link.toString().replace("X-Amz-Expires=60", "X-Amz-Expires=6000"));
        assertThat(http.send(HttpRequest.newBuilder(tampered).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode())
            .isEqualTo(403);
    }

    @Test
    void deliveryKeysLiveInTheDeliveryBucketAndRecordingsCopyAcross() throws Exception {
        String recording = "live/ch-1/" + UUID.randomUUID() + "/media/hls/720p30/1.ts";
        String delivered = "delivery/org/o1/asset/" + UUID.randomUUID() + "/g1/hls/720p/1.ts";
        storage.put(recording, "segment".getBytes(StandardCharsets.US_ASCII), "video/mp2t");

        storage.copy(recording, delivered);

        assertThat(s3.headObject(b -> b.bucket("ohara-delivery").key(delivered)).contentLength()).isEqualTo(7);
        assertThat(s3.headObject(b -> b.bucket("ohara-source").key(recording)).contentLength()).isEqualTo(7);
        assertThat(storage.head(delivered)).isPresent();
        assertThat(storage.head("delivery/nothing/here.ts")).isEmpty();
        assertThat(storage.head("org/nothing/here.mp4")).isEmpty();
    }
}
