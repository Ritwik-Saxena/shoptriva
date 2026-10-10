package com.shoptriva.order.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class OrderItemResponse {

    Long orderItemId;

    Long productId;

    String productName;

    Integer quantity;

    BigDecimal unitPrice;

    BigDecimal lineTotal;
}
