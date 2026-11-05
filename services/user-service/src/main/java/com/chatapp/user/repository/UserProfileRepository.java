package com.chatapp.user.repository;

import java.util.UUID;

import com.chatapp.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {
}
