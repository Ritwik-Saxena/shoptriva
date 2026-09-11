package com.shoptriva.catalog.mapper;

import com.shoptriva.catalog.dto.ProductResponse;
import com.shoptriva.catalog.entity.Product;

public class ProductMapper {

    private ProductMapper() {
        // Utility class
    }

    public static ProductResponse toResponse(Product product) {

        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .categoryId(product.getCategory().getId())
                .categoryName(product.getCategory().getName())
                .build();
    }
}