package com.shoptriva.auth.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AuthResponse {

    String accessToken;

    String tokenType;

    Long userId;

    String email;

    String role;
}