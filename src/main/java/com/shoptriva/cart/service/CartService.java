package com.shoptriva.cart.service;

import com.shoptriva.cart.dto.AddToCartRequest;
import com.shoptriva.cart.dto.CartResponse;
import com.shoptriva.cart.dto.UpdateCartItemRequest;

public interface CartService {

    CartResponse getCart();

    CartResponse addItem(AddToCartRequest request);

    CartResponse updateItemQuantity(
            Long itemId,
            UpdateCartItemRequest request
    );

    CartResponse removeItem(Long itemId);

    CartResponse clearCart();
}