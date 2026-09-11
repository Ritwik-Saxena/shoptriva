package com.shoptriva.common.response;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class ApiErrorResponse {

    LocalDateTime timestamp;
    int status;
    String error;
    String message;
    String path;
}