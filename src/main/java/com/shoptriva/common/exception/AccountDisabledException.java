package com.shoptriva.common.exception;

public class AccountDisabledException extends RuntimeException {

    public AccountDisabledException() {
        super("User account is disabled");
    }
}