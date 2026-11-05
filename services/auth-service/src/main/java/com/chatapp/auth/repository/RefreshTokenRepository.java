package com.chatapp.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Ends every refresh token of one device session. */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.sessionId = :sessionId and t.revoked = false")
    int revokeAllBySessionId(@Param("sessionId") UUID sessionId);

    /** Ends every refresh token of a user (used after a password reset). */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.userId = :userId and t.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);

    List<RefreshToken> findBySessionId(UUID sessionId);
}
