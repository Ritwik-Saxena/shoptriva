package com.shoptriva.cart.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class CartItemResponse {

    Long itemId;
    Long productId;
    String productName;
    BigDecimal price;
    Integer quantity;
    BigDecimal subtotal;
}