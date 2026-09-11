package com.shoptriva.auth.service;

import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.repository.UserRepository;
import com.shoptriva.auth.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentUserServiceImpl implements CurrentUserService {

    private final UserRepository userRepository;

    @Override
    public User getCurrentUser() {

        UserPrincipal principal = getPrincipal();

        return userRepository.findById(principal.getId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Authenticated user no longer exists"
                        ));
    }

    @Override
    public Long getCurrentUserId() {

        return getPrincipal().getId();
    }

    private UserPrincipal getPrincipal() {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null ||
                !authentication.isAuthenticated() ||
                !(authentication.getPrincipal()
                        instanceof UserPrincipal principal)) {

            throw new IllegalStateException(
                    "No authenticated user found"
            );
        }

        return principal;
    }
}