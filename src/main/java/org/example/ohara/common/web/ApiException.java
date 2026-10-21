package org.example.ohara.common.web;

import org.springframework.http.HttpStatus;

/** Base for errors that map to a specific HTTP status and a stable, client-facing {@code code}. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

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

    public static class Gone extends ApiException {
        public Gone(String code, String message) {
            super(HttpStatus.GONE, code, message);
        }
    }
}
