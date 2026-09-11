package com.shoptriva.catalog.service;

import com.shoptriva.catalog.dto.ProductRequest;
import com.shoptriva.catalog.dto.ProductResponse;
import com.shoptriva.catalog.dto.ProductSummaryResponse;
import com.shoptriva.catalog.entity.Category;
import com.shoptriva.catalog.entity.Product;
import com.shoptriva.catalog.mapper.ProductMapper;
import com.shoptriva.catalog.repository.CategoryRepository;
import com.shoptriva.catalog.repository.ProductRepository;
import com.shoptriva.catalog.specification.ProductSpecification;
import com.shoptriva.common.exception.CategoryNotFoundException;
import com.shoptriva.common.exception.ProductNotFoundException;
import com.shoptriva.common.mapper.PageMapper;
import com.shoptriva.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    @Override
    public ProductResponse createProduct(ProductRequest request) {

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() ->
                        new CategoryNotFoundException(request.getCategoryId()));

        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .category(category)
                .build();

        Product savedProduct = productRepository.save(product);

        return ProductMapper.toResponse(savedProduct);
    }

    @Override
    public ProductResponse getProductById(Long id) {

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        return ProductMapper.toResponse(product);
    }

    @Override
    public PageResponse<ProductResponse> searchProducts(
            int page,
            int size,
            String sortBy,
            String direction,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            String search) {

        Sort sort = direction.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable =
                PageRequest.of(page, size, sort);

        Specification<Product> specification =
                Specification
                        .where(ProductSpecification.hasCategory(categoryId))
                        .and(ProductSpecification.priceGreaterThanOrEqualTo(minPrice))
                        .and(ProductSpecification.priceLessThanOrEqualTo(maxPrice))
                        .and(ProductSpecification.nameContains(search));

        Page<Product> products =
                productRepository.findAll(
                        specification,
                        pageable
                );

        return PageMapper.toResponse(
                products,
                ProductMapper::toResponse
        );
    }
    @Override
    public List<ProductSummaryResponse> getProductSummaries() {

        return productRepository.findProductSummaries()
                .stream()
                .map(product -> ProductSummaryResponse.builder()
                        .id(product.getId())
                        .name(product.getName())
                        .price(product.getPrice())
                        .categoryName(product.getCategoryName())
                        .build())
                .toList();
    }
}