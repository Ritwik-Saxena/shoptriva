package com.shoptriva.common.exception;

public class QuantityLimitException extends RuntimeException {

    public QuantityLimitException() {
        super("Quantity exceeds the supported limit");
    }

    public static int add(int left, int right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            throw new QuantityLimitException();
        }
    }
}
