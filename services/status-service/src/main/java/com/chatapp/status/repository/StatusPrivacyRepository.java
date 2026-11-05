package com.chatapp.status.repository;

import java.util.UUID;

import com.chatapp.status.entity.StatusPrivacy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusPrivacyRepository extends JpaRepository<StatusPrivacy, UUID> {
}
