package com.shoptriva.common.exception;

public class CartItemNotFoundException extends RuntimeException {

    public CartItemNotFoundException(Long itemId) {
        super("Cart item with id " + itemId + " not found");
    }
}