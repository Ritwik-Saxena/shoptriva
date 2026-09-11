package com.shoptriva.catalog.service;

import com.shoptriva.catalog.dto.ProductRequest;
import com.shoptriva.catalog.dto.ProductResponse;
import com.shoptriva.catalog.dto.ProductSummaryResponse;
import com.shoptriva.common.response.PageResponse;

import java.math.BigDecimal;
import java.util.List;

public interface ProductService {

    ProductResponse createProduct(ProductRequest request);

    ProductResponse getProductById(Long id);

    PageResponse<ProductResponse> searchProducts(
            int page,
            int size,
            String sortBy,
            String direction,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            String search
    );

    List<ProductSummaryResponse> getProductSummaries();
}