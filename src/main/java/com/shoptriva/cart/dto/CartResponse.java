package com.shoptriva.cart.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
@Builder
public class CartResponse {

    Long cartId;
    List<CartItemResponse> items;
    Integer totalItems;
    BigDecimal totalAmount;
}