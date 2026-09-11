package com.shoptriva.cart.controller;

import com.shoptriva.cart.dto.AddToCartRequest;
import com.shoptriva.cart.dto.CartResponse;
import com.shoptriva.cart.dto.UpdateCartItemRequest;
import com.shoptriva.cart.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public CartResponse getCart() {
        return cartService.getCart();
    }

    @PostMapping("/items")
    public CartResponse addItem(
            @Valid @RequestBody AddToCartRequest request) {

        return cartService.addItem(request);
    }

    @PutMapping("/items/{itemId}")
    public CartResponse updateItemQuantity(
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateCartItemRequest request) {

        return cartService.updateItemQuantity(
                itemId,
                request
        );
    }

    @DeleteMapping("/items/{itemId}")
    public CartResponse removeItem(
            @PathVariable Long itemId) {

        return cartService.removeItem(itemId);
    }

    @DeleteMapping
    public CartResponse clearCart() {
        return cartService.clearCart();
    }
}