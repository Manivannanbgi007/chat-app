package com.chatapp.user.repository;

import java.util.UUID;

import com.chatapp.user.entity.UserPrivacySettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivacyRepository extends JpaRepository<UserPrivacySettings, UUID> {
}
