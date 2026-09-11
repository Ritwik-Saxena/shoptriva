package com.shoptriva.common.mapper;

import com.shoptriva.common.response.PageResponse;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public final class PageMapper {

    private PageMapper() {
    }

    public static <T, R> PageResponse<R> toResponse(
            Page<T> page,
            Function<T, R> mapper) {

        List<R> content = page.getContent()
                .stream()
                .map(mapper)
                .toList();

        return PageResponse.<R>builder()
                .content(content)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
    }
}