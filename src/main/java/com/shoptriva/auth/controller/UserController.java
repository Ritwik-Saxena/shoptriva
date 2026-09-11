package com.shoptriva.auth.controller;

import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final CurrentUserService currentUserService;

    @GetMapping("/me")
    public String getCurrentUser() {

        User user = currentUserService.getCurrentUser();

        return "Logged in as: "
                + user.getEmail()
                + " | Role: "
                + user.getRole();
    }
}