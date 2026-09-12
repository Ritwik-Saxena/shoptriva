package com.shoptriva.common.exception;

public class WishlistItemNotFoundException extends RuntimeException {

    public WishlistItemNotFoundException(Long itemId) {
        super("Wishlist item with id " + itemId + " not found");
    }
}
