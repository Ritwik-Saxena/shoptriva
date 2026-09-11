package com.shoptriva.catalog.mapper;

import com.shoptriva.catalog.dto.CategoryRequest;
import com.shoptriva.catalog.dto.CategoryResponse;
import com.shoptriva.catalog.entity.Category;

public class CategoryMapper {

    public static Category toEntity(CategoryRequest request) {
        return Category.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();
    }

    public static CategoryResponse toResponse(Category category) {
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .build();
    }
}