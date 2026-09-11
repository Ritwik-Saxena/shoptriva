package com.shoptriva.catalog.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class ProductSummaryResponse {

    Long id;
    String name;
    BigDecimal price;
    String categoryName;
}