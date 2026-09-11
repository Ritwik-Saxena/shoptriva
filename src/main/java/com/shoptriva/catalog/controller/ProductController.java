package com.shoptriva.catalog.controller;

import com.shoptriva.catalog.dto.ProductRequest;
import com.shoptriva.catalog.dto.ProductResponse;
import com.shoptriva.catalog.dto.ProductSummaryResponse;
import com.shoptriva.catalog.service.ProductService;
import com.shoptriva.common.response.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ProductResponse createProduct(
            @Valid @RequestBody ProductRequest request) {

        return productService.createProduct(request);
    }

    @GetMapping("/summary")
    public List<ProductSummaryResponse> getProductSummaries() {
        return productService.getProductSummaries();
    }

//    @GetMapping
//    public Page<ProductResponse> getAllProducts(
//            @RequestParam(defaultValue = "0") int page,
//            @RequestParam(defaultValue = "10") int size,
//            @RequestParam(defaultValue = "id") String sortBy,
//            @RequestParam(defaultValue = "asc") String direction) {
//
//        return productService.getAllProducts(
//                page,
//                size,
//                sortBy,
//                direction
//        );
//    }
@GetMapping
public PageResponse<ProductResponse> searchProducts(

        @RequestParam(defaultValue = "0")
        int page,

        @RequestParam(defaultValue = "10")
        int size,

        @RequestParam(defaultValue = "id")
        String sortBy,

        @RequestParam(defaultValue = "asc")
        String direction,

        @RequestParam(required = false)
        Long categoryId,

        @RequestParam(required = false)
        BigDecimal minPrice,

        @RequestParam(required = false)
        BigDecimal maxPrice,

        @RequestParam(required = false)
        String search) {

    return productService.searchProducts(
            page,
            size,
            sortBy,
            direction,
            categoryId,
            minPrice,
            maxPrice,
            search
    );
}

    @GetMapping("/{id}")
    public ProductResponse getProductById(
            @PathVariable Long id) {

        return productService.getProductById(id);
    }
}