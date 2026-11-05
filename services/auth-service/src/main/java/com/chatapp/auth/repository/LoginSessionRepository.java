package com.chatapp.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.auth.entity.LoginSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginSessionRepository extends JpaRepository<LoginSession, UUID> {

    List<LoginSession> findByUserIdAndRevokedFalseOrderByLastActiveAtDesc(UUID userId);

    Optional<LoginSession> findByIdAndUserIdAndRevokedFalse(UUID id, UUID userId);
}
