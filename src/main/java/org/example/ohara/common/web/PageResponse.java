package org.example.ohara.common.web;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Page of results with 0-based {@code page}. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static final int MAX_SIZE = 200;

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
            page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public static int clampSize(int size) {
        return Math.clamp(size, 1, MAX_SIZE);
    }
}
