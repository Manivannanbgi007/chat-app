package com.chatapp.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant after);

    @Modifying
    @Query("update PasswordResetToken t set t.used = true where t.userId = :userId and t.used = false")
    int invalidateUnused(@Param("userId") UUID userId);
}
