package org.example.ohara.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

/**
 * Optimistic concurrency for admin edits (docs 08 §1): versioned resources are served with {@code ETag: "<version>"},
 * and an update carrying {@code If-Match} is refused with 412 when the resource changed since it was read, instead of
 * silently overwriting someone else's edit. Requests without If-Match are still accepted, for now; making it mandatory
 * (428) is a configuration switch once every client sends it.
 */
public final class Etags {

    private Etags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /** Call in an update, before changing anything, with the entity's current version. */
    public static void checkIfMatch(long currentVersion) {
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
            ? attrs.getRequest() : null;
        String ifMatch = request == null ? null : request.getHeader(HttpHeaders.IF_MATCH);
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.strip().equals("*")) {
            return;
        }
        String current = of(currentVersion);
        boolean matches = Arrays.stream(ifMatch.split(","))
            .map(String::strip)
            .map(tag -> tag.startsWith("W/") ? tag.substring(2) : tag)
            .anyMatch(current::equals);
        if (!matches) {
            throw new ApiException.PreconditionFailed("precondition_failed",
                "This was changed by someone else since you loaded it; reload and try again").with("currentETag", current);
        }
    }
}
