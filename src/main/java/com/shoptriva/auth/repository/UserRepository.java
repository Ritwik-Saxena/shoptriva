package com.shoptriva.auth.repository;

import com.shoptriva.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // Stable mutex for checkout and every cart write, including initial cart creation.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> lockForCartWrite(@Param("userId") Long userId);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
