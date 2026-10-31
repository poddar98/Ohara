package org.example.ohara.common.web;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/** Base for errors that map to a specific HTTP status and a stable, client-facing {@code code}. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** Extra problem-details members (e.g. a list of failed checks). */
    public ApiException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public static class BadRequest extends ApiException {
        public BadRequest(String code, String message) {
            super(HttpStatus.BAD_REQUEST, code, message);
        }
    }

    public static class Unauthenticated extends ApiException {
        public Unauthenticated(String code, String message) {
            super(HttpStatus.UNAUTHORIZED, code, message);
        }
    }

    public static class Forbidden extends ApiException {
        public Forbidden(String code, String message) {
            super(HttpStatus.FORBIDDEN, code, message);
        }
    }

    public static class NotFound extends ApiException {
        public NotFound(String code, String message) {
            super(HttpStatus.NOT_FOUND, code, message);
        }
    }

    public static class Conflict extends ApiException {
        public Conflict(String code, String message) {
            super(HttpStatus.CONFLICT, code, message);
        }
    }

    /** Well-formed request that can't be applied to the current state (e.g. referenced user isn't registered). */
    public static class Unprocessable extends ApiException {
        public Unprocessable(String code, String message) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
        }
    }

    public static class TooManyRequests extends ApiException {
        public TooManyRequests(String code, String message) {
            super(HttpStatus.TOO_MANY_REQUESTS, code, message);
        }
    }

    public static class Gone extends ApiException {
        public Gone(String code, String message) {
            super(HttpStatus.GONE, code, message);
        }
    }

    /** The client's If-Match no longer matches: someone else changed the resource since it was read. */
    public static class PreconditionFailed extends ApiException {
        public PreconditionFailed(String code, String message) {
            super(HttpStatus.PRECONDITION_FAILED, code, message);
        }
    }

    /** A dependency (e.g. an AI provider) is temporarily unavailable; the client may retry later. */
    public static class ServiceUnavailable extends ApiException {
        public ServiceUnavailable(String code, String message) {
            super(HttpStatus.SERVICE_UNAVAILABLE, code, message);
        }
    }
}
