package com.shoptriva.catalog.projection;

import java.math.BigDecimal;

public interface ProductSummary {

    Long getId();

    String getName();

    BigDecimal getPrice();

    String getCategoryName();
}