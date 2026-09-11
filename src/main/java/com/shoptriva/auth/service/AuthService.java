package com.shoptriva.auth.service;

import com.shoptriva.auth.dto.AuthResponse;
import com.shoptriva.auth.dto.LoginRequest;
import com.shoptriva.auth.dto.RegisterRequest;

public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);
}