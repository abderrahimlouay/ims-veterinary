package com.imsvet.ims.common.paging;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The paged response envelope of api.md §1: {@code {"content": [...], "page": {...}}}.
 */
public record PageResponse<T>(List<T> content, PageMetadata page) {

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {
    }

    public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, ? extends T> mapper) {
        List<T> content = page.getContent().stream().<T>map(mapper).toList();
        return new PageResponse<>(content,
                new PageMetadata(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }
}
