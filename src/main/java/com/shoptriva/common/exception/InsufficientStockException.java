package com.shoptriva.common.exception;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(
            Long productId,
            Integer requestedQuantity,
            Integer availableQuantity
    ) {
        super(
                "Insufficient stock for product id "
                        + productId
                        + ". Requested: "
                        + requestedQuantity
                        + ", available: "
                        + availableQuantity
        );
    }
}
