package com.shoptriva.auth.service;

import com.shoptriva.auth.entity.User;

public interface CurrentUserService {

    User getCurrentUser();

    Long getCurrentUserId();
}