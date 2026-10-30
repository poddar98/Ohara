package org.example.ohara.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Renders errors as RFC 9457 problem details with a stable {@code code} property.
 * Spring MVC's own exceptions (404, 405, unreadable body, …) are handled by the superclass.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String TYPE_BASE = "https://errors.ohara.example/";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest request) {
        ResponseEntity<ProblemDetail> response = problem(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
        ex.getProperties().forEach(response.getBody()::setProperty);
        return response;
    }

    /** Safety net for races that slip past service-level checks (e.g. two concurrent inserts of the same unique row). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        logger.warn("Constraint violation on " + request.getRequestURI() + ": " + ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "conflict", "The request conflicts with the current state of the resource", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid credentials", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "You are not allowed to perform this action", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage()))
            .toList();
        ProblemDetail body = ex.getBody();
        body.setType(URI.create(TYPE_BASE + "validation-failed"));
        body.setDetail("Request validation failed");
        body.setProperty("code", "validation_failed");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create(TYPE_BASE + code.replace('_', '-')));
        body.setTitle(status.getReasonPhrase());
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
