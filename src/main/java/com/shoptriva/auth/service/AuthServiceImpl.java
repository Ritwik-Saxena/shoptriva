package com.shoptriva.auth.service;

import com.shoptriva.auth.dto.AuthResponse;
import com.shoptriva.auth.dto.LoginRequest;
import com.shoptriva.auth.dto.RegisterRequest;
import com.shoptriva.auth.entity.Role;
import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.jwt.JwtService;
import com.shoptriva.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.shoptriva.common.exception.AccountDisabledException;
import com.shoptriva.common.exception.DuplicateEmailException;
import com.shoptriva.common.exception.InvalidCredentialsException;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException(request.getEmail());
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.CUSTOMER)
                .enabled(true)
                .build();

        User savedUser = userRepository.save(user);

        String token = jwtService.generateToken(savedUser);

        return buildResponse(savedUser, token);
    }

    @Override
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(InvalidCredentialsException::new);

        if (!user.isEnabled()) {
            throw new AccountDisabledException();
        }

        if (!passwordEncoder.matches(
                request.getPassword(),
                user.getPassword())) {

            throw new InvalidCredentialsException();
        }

        String token = jwtService.generateToken(user);

        return buildResponse(user, token);
    }

    private AuthResponse buildResponse(User user, String token) {

        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .userId(user.getId())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }
}