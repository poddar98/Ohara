package org.example.ohara.content.infrastructure.storage;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The "S3 endpoint" of the local storage stand-in. Authentication is the signed token in the URL, exactly like an S3
 * presigned URL; bad, expired or wrong-operation tokens get 403.
 */
@RestController
@ConditionalOnProperty(name = "ohara.storage.provider", havingValue = "local", matchIfMissing = true)
class LocalStorageController {

    private final LocalObjectStorage storage;

    LocalStorageController(LocalObjectStorage storage) {
        this.storage = storage;
    }

    @PutMapping("/dev-storage/parts/{token}")
    ResponseEntity<Void> putPart(@PathVariable String token, HttpServletRequest request) throws IOException {
        String[] claims = storage.verify(token).filter(c -> "PUT_PART".equals(c[0])).orElse(null);
        if (claims == null) {
            return ResponseEntity.status(403).build();
        }
        try {
            String etag = storage.writePart(claims, request.getInputStream());
            return ResponseEntity.ok().eTag(etag).build();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/dev-storage/objects/{token}")
    ResponseEntity<Resource> get(@PathVariable String token) {
        String[] claims = storage.verify(token).filter(c -> "GET".equals(c[0])).orElse(null);
        if (claims == null) {
            return ResponseEntity.status(403).build();
        }
        Path file = storage.fileForDownload(claims[1]);
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .contentType(MediaTypeFactory.getMediaType(claims[1]).orElse(MediaType.APPLICATION_OCTET_STREAM));
        if (!claims[2].isEmpty()) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + LocalObjectStorage.encodeFileName(claims[2]));
        }
        return response.body(new FileSystemResource(file));
    }
}
