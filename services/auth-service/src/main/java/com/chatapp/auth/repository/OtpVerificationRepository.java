package com.chatapp.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.auth.entity.OtpVerification;
import com.chatapp.auth.enums.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OtpVerificationRepository extends JpaRepository<OtpVerification, UUID> {

    Optional<OtpVerification> findFirstByUserIdAndPurposeAndUsedFalseOrderByCreatedAtDesc(UUID userId, OtpPurpose purpose);

    Optional<OtpVerification> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(UUID userId, OtpPurpose purpose);

    long countByUserIdAndPurposeAndCreatedAtAfter(UUID userId, OtpPurpose purpose, Instant after);

    /** When a new code is sent, older unused codes stop working. */
    @Modifying
    @Query("update OtpVerification o set o.used = true where o.userId = :userId and o.purpose = :purpose and o.used = false")
    int invalidateUnused(@Param("userId") UUID userId, @Param("purpose") OtpPurpose purpose);
}
