package org.example.ohara.content.infrastructure.storage;

import org.example.ohara.content.application.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Local-disk stand-in for S3 (development and tests). Presigned URLs point at {@link LocalStorageController} and carry
 * an HMAC-signed token with the operation, key, part and expiry, so they behave like real presigned URLs: anyone with
 * the URL can use it until it expires, and nothing else.
 */
@Component
@ConditionalOnProperty(name = "ohara.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage {

    static final String SEP = "\n";

    private final Path root;
    private final String publicBaseUrl;
    private final byte[] signingKey = new byte[32];

    public LocalObjectStorage(@Value("${ohara.storage.local.root:./var/storage}") String root,
                              @Value("${ohara.storage.local.public-base-url:http://localhost:8080}") String publicBaseUrl) throws IOException {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl;
        Files.createDirectories(this.root.resolve(".uploads"));
        new SecureRandom().nextBytes(signingKey);
    }

    @Override
    public String startMultipart(String key, String contentType) {
        objectPath(key);
        String uploadId = UUID.randomUUID().toString();
        try {
            Files.createDirectories(uploadDir(uploadId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return uploadId;
    }

    @Override
    public PresignedPart presignPart(String key, String uploadId, int partNumber, long maxBytes, Duration ttl) {
        Instant expires = Instant.now().plus(ttl);
        String token = sign(String.join(SEP, "PUT_PART", key, uploadId, String.valueOf(partNumber),
            String.valueOf(maxBytes), String.valueOf(expires.getEpochSecond())));
        return new PresignedPart(partNumber, URI.create(publicBaseUrl + "/dev-storage/parts/" + token), expires);
    }

    @Override
    public List<UploadedPart> listParts(String key, String uploadId) {
        Path dir = uploadDir(uploadId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().matches("\\d+"))
                .map(p -> new UploadedPart(Integer.parseInt(p.getFileName().toString()), size(p), etag(p)))
                .sorted(java.util.Comparator.comparingInt(UploadedPart::partNumber))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public StoredObject completeMultipart(String key, String uploadId, List<CompletedPart> parts) {
        Path dir = uploadDir(uploadId);
        List<Path> ordered = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            CompletedPart part = parts.get(i);
            if (part.partNumber() != i + 1) {
                throw new StorageMismatchException("Parts must be numbered 1.." + parts.size() + " in order");
            }
            Path file = dir.resolve(String.valueOf(part.partNumber()));
            if (!Files.exists(file) || !etag(file).equals(part.etag())) {
                throw new StorageMismatchException("Part " + part.partNumber() + " is missing or its ETag doesn't match");
            }
            ordered.add(file);
        }
        Path target = objectPath(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".assembling", null);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                for (Path part : ordered) {
                    try (InputStream in = new DigestInputStream(Files.newInputStream(part), sha)) {
                        in.transferTo(out);
                    }
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            deleteDir(dir);
            return new StoredObject(key, Files.size(target), UUID.randomUUID().toString(), HexFormat.of().formatHex(sha.digest()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void abortMultipart(String key, String uploadId) {
        deleteDir(uploadDir(uploadId));
    }

    @Override
    public Optional<StoredObject> head(String key) {
        Path file = objectPath(key);
        return Files.exists(file) ? Optional.of(new StoredObject(key, size(file), null, null)) : Optional.empty();
    }

    @Override
    public URI presignGet(String key, Duration ttl, String downloadFileName) {
        Instant expires = Instant.now().plus(ttl);
        String token = sign(String.join(SEP, "GET", key, downloadFileName == null ? "" : downloadFileName,
            String.valueOf(expires.getEpochSecond())));
        return URI.create(publicBaseUrl + "/dev-storage/objects/" + token);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(objectPath(key));
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try {
            Path target = objectPath(key);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- used by LocalStorageController ------------------------------------------------------------

    /** Stores one uploaded part from a verified PUT_PART token; returns its ETag. */
    String writePart(String[] claims, InputStream body) throws IOException {
        String uploadId = claims[2];
        int partNumber = Integer.parseInt(claims[3]);
        long maxBytes = Long.parseLong(claims[4]);
        Path dir = uploadDir(uploadId);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Upload is no longer open");
        }
        Path tmp = Files.createTempFile(dir, ".part", null);
        long written;
        try (InputStream limited = body; OutputStream out = Files.newOutputStream(tmp)) {
            written = limited.transferTo(new LimitedOutputStream(out, maxBytes));
        }
        if (written == 0) {
            Files.deleteIfExists(tmp);
            throw new IllegalArgumentException("Empty part");
        }
        Path part = dir.resolve(String.valueOf(partNumber));
        Files.move(tmp, part, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return etag(part);
    }

    Path fileForDownload(String key) {
        return objectPath(key);
    }

    /** Verifies signature and expiry; returns the claims or empty. */
    Optional<String[]> verify(String token) {
        try {
            return verifyStrict(token);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private Optional<String[]> verifyStrict(String token) {
        int dot = token.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(hmac(payload).getBytes(StandardCharsets.UTF_8), token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        String[] claims = payload.split(SEP, -1);
        long expires = Long.parseLong(claims[claims.length - 1]);
        return Instant.now().getEpochSecond() <= expires ? Optional.of(claims) : Optional.empty();
    }

    // --- helpers -----------------------------------------------------------------------------------

    private String sign(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + hmac(payload);
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Keys are server-generated, but refuse anything that could escape the storage root. */
    private Path objectPath(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root) || key.startsWith(".") || key.contains("..")) {
            throw new IllegalArgumentException("Invalid object key");
        }
        return resolved;
    }

    private Path uploadDir(String uploadId) {
        if (!uploadId.matches("[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("Invalid upload id");
        }
        return root.resolve(".uploads").resolve(uploadId);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String etag(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                md5.update(buffer, 0, n);
            }
            return "\"" + HexFormat.of().formatHex(md5.digest()) + "\"";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String encodeFileName(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Refuses to write more than {@code limit} bytes (a presigned part has a size cap). */
    private static final class LimitedOutputStream extends OutputStream {
        private final OutputStream out;
        private final long limit;
        private long count;

        LimitedOutputStream(OutputStream out, long limit) {
            this.out = out;
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            check(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check(len);
            out.write(b, off, len);
        }

        private void check(int len) {
            count += len;
            if (count > limit) {
                throw new IllegalArgumentException("Part exceeds the presigned size limit");
            }
        }
    }
}
