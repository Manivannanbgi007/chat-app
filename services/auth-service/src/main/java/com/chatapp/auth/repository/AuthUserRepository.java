package com.chatapp.auth.repository;

import java.util.Optional;
import java.util.UUID;

import com.chatapp.auth.entity.AuthUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthUserRepository extends JpaRepository<AuthUser, UUID> {

    Optional<AuthUser> findByEmail(String email);

    Optional<AuthUser> findByPhone(String phone);

    Optional<AuthUser> findByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    boolean existsByUsername(String username);
}
