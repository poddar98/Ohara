package org.example.ohara.common.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Adds the ETag header to every response whose body is a single {@link Versioned} resource. */
@RestControllerAdvice
class EtagResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType, Class converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Versioned versioned && !response.getHeaders().containsHeader(HttpHeaders.ETAG)) {
            response.getHeaders().setETag(Etags.of(versioned.version()));
        }
        return body;
    }
}
