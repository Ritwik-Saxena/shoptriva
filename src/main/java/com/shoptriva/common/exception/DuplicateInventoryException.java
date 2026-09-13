package com.shoptriva.common.exception;

public class DuplicateInventoryException extends RuntimeException {

    public DuplicateInventoryException(Long productId) {
        super("Inventory already exists for product id " + productId);
    }
}
