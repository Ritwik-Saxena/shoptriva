package com.shoptriva.catalog.repository;

import com.shoptriva.catalog.entity.Product;
import com.shoptriva.catalog.projection.ProductSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProductRepository
        extends JpaRepository<Product, Long>,
        JpaSpecificationExecutor<Product> {

    boolean existsByName(String name);

    @Query("""
            SELECT
                p.id AS id,
                p.name AS name,
                p.price AS price,
                c.name AS categoryName
            FROM Product p
            JOIN p.category c
            """)
    List<ProductSummary> findProductSummaries();
}