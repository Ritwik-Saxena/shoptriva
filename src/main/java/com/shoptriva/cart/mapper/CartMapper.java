package com.shoptriva.cart.mapper;

import com.shoptriva.cart.dto.CartItemResponse;
import com.shoptriva.cart.dto.CartResponse;
import com.shoptriva.cart.entity.Cart;
import com.shoptriva.cart.entity.CartItem;

import java.math.BigDecimal;
import java.util.List;

public final class CartMapper {

    private CartMapper() {
    }

    public static CartItemResponse toItemResponse(CartItem item) {

        BigDecimal subtotal = item.getProduct()
                .getPrice()
                .multiply(BigDecimal.valueOf(item.getQuantity()));

        return CartItemResponse.builder()
                .itemId(item.getId())
                .productId(item.getProduct().getId())
                .productName(item.getProduct().getName())
                .price(item.getProduct().getPrice())
                .quantity(item.getQuantity())
                .subtotal(subtotal)
                .build();
    }

    public static CartResponse toResponse(
            Cart cart,
            List<CartItem> items) {

        List<CartItemResponse> itemResponses = items.stream()
                .map(CartMapper::toItemResponse)
                .toList();

        int totalItems = items.stream()
                .mapToInt(CartItem::getQuantity)
                .sum();

        BigDecimal totalAmount = itemResponses.stream()
                .map(CartItemResponse::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return CartResponse.builder()
                .cartId(cart.getId())
                .items(itemResponses)
                .totalItems(totalItems)
                .totalAmount(totalAmount)
                .build();
    }
}