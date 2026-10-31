package org.example.ohara.content.application;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Object storage port, shaped like S3: clients upload parts straight to presigned URLs, so file bytes never pass
 * through the API. The local adapter stands in until the AWS account exists (Q-04); an S3 adapter implements the same.
 */
public interface ObjectStorage {

    record StoredObject(String key, long size, String versionId, String sha256) {
    }

    record PresignedPart(int partNumber, URI url, Instant expiresAt) {
    }

    record UploadedPart(int partNumber, long size, String etag) {
    }

    record CompletedPart(int partNumber, String etag) {
    }

    /** The stored parts don't match what the client says it uploaded. */
    class StorageMismatchException extends RuntimeException {
        public StorageMismatchException(String message) {
            super(message);
        }
    }

    String startMultipart(String key, String contentType);

    /** A URL the client PUTs one part to; parts larger than {@code maxBytes} are refused. */
    PresignedPart presignPart(String key, String uploadId, int partNumber, long maxBytes, Duration ttl);

    List<UploadedPart> listParts(String key, String uploadId);

    StoredObject completeMultipart(String key, String uploadId, List<CompletedPart> parts);

    void abortMultipart(String key, String uploadId);

    Optional<StoredObject> head(String key);

    /** A short-lived download URL. */
    URI presignGet(String key, Duration ttl, String downloadFileName);

    InputStream open(String key) throws IOException;

    void put(String key, byte[] content, String contentType);

    /** Server-side copy (S3 CopyObject): the bytes never pass through the application. */
    void copy(String fromKey, String toKey);
}
