package com.chatapp.notification.repository;

import java.util.UUID;

import com.chatapp.notification.entity.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
}
