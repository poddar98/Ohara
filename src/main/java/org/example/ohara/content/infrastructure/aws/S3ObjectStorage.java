package org.example.ohara.content.infrastructure.aws;

import org.example.ohara.content.application.ObjectStorage;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link ObjectStorage} on Amazon S3 (docs 06 §3). Two private buckets: keys under {@code delivery/} live in the
 * delivery bucket (CloudFront's origin), everything else (uploaded sources, live recordings) in the versioned source
 * bucket. Clients upload parts straight to presigned URLs; each URL is signed for the part's exact length, so a client
 * can't push more than the declared size.
 * <p>
 * S3's multipart checksums are per part, not of the whole file, so {@link #completeMultipart} returns no SHA-256
 * (the upload flow then relies on size and part ETags); the object version id identifies the exact bytes.
 */
public class S3ObjectStorage implements ObjectStorage {

    static final String DELIVERY_PREFIX = "delivery/";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String sourceBucket;
    private final String deliveryBucket;

    public S3ObjectStorage(S3Client s3, S3Presigner presigner, String sourceBucket, String deliveryBucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.sourceBucket = sourceBucket;
        this.deliveryBucket = deliveryBucket;
    }

    String bucketOf(String key) {
        return key.startsWith(DELIVERY_PREFIX) ? deliveryBucket : sourceBucket;
    }

    @Override
    public String startMultipart(String key, String contentType) {
        return s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
            .bucket(bucketOf(key)).key(key).contentType(contentType).build()).uploadId();
    }

    @Override
    public PresignedPart presignPart(String key, String uploadId, int partNumber, long maxBytes, Duration ttl) {
        PresignedUploadPartRequest presigned = presigner.presignUploadPart(r -> r
            .signatureDuration(ttl)
            .uploadPartRequest(UploadPartRequest.builder()
                .bucket(bucketOf(key)).key(key).uploadId(uploadId).partNumber(partNumber).contentLength(maxBytes).build()));
        return new PresignedPart(partNumber, toUri(presigned.url()), presigned.expiration());
    }

    @Override
    public List<UploadedPart> listParts(String key, String uploadId) {
        List<UploadedPart> parts = new ArrayList<>();
        try {
            s3.listPartsPaginator(ListPartsRequest.builder().bucket(bucketOf(key)).key(key).uploadId(uploadId).build())
                .parts()
                .forEach(p -> parts.add(new UploadedPart(p.partNumber(), p.size(), p.eTag())));
        } catch (NoSuchUploadException gone) {
            return List.of();
        }
        return parts;
    }

    @Override
    public StoredObject completeMultipart(String key, String uploadId, List<CompletedPart> parts) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).partNumber() != i + 1) {
                throw new StorageMismatchException("Parts must be numbered 1.." + parts.size() + " in order");
            }
        }
        CompleteMultipartUploadResponse done;
        try {
            done = s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucketOf(key)).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(parts.stream()
                    .map(p -> software.amazon.awssdk.services.s3.model.CompletedPart.builder()
                        .partNumber(p.partNumber()).eTag(p.etag()).build())
                    .toList()).build())
                .build());
        } catch (S3Exception e) {
            if ("InvalidPart".equals(code(e)) || "InvalidPartOrder".equals(code(e)) || "EntityTooSmall".equals(code(e))
                || e instanceof NoSuchUploadException) {
                throw new StorageMismatchException("The uploaded parts don't match: " + e.awsErrorDetails().errorMessage());
            }
            throw e;
        }
        HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(bucketOf(key)).key(key)
            .versionId(done.versionId()).build());
        return new StoredObject(key, head.contentLength(), done.versionId(), null);
    }

    @Override
    public void abortMultipart(String key, String uploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucketOf(key)).key(key).uploadId(uploadId).build());
        } catch (NoSuchUploadException alreadyGone) {
            // idempotent
        }
    }

    @Override
    public Optional<StoredObject> head(String key) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(bucketOf(key)).key(key).build());
            return Optional.of(new StoredObject(key, head.contentLength(), head.versionId(), null));
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl, String downloadFileName) {
        GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(bucketOf(key)).key(key);
        if (downloadFileName != null && !downloadFileName.isBlank()) {
            get.responseContentDisposition(contentDisposition(downloadFileName));
        }
        PresignedGetObjectRequest presigned = presigner.presignGetObject(r -> r.signatureDuration(ttl).getObjectRequest(get.build()));
        return toUri(presigned.url());
    }

    @Override
    public InputStream open(String key) {
        ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder().bucket(bucketOf(key)).key(key).build());
        return in; // the caller closes it, which releases the connection
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucketOf(key)).key(key).contentType(contentType).build(),
            RequestBody.fromBytes(content));
    }

    @Override
    public void copy(String fromKey, String toKey) {
        s3.copyObject(CopyObjectRequest.builder()
            .sourceBucket(bucketOf(fromKey)).sourceKey(fromKey)
            .destinationBucket(bucketOf(toKey)).destinationKey(toKey)
            .build());
    }

    /** RFC 6266: an ASCII fallback plus the exact UTF-8 name. */
    static String contentDisposition(String fileName) {
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'").replace("\\", "_");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private static String code(S3Exception e) {
        return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    }

    private static URI toUri(java.net.URL url) {
        try {
            return url.toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
