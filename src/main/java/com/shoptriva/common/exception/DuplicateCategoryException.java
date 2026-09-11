package com.shoptriva.common.exception;

public class DuplicateCategoryException extends RuntimeException {

    public DuplicateCategoryException(String name) {
        super("Category already exists with name: " + name);
    }
}