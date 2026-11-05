package com.chatapp.user.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_profiles")
@Getter
@Setter
@NoArgsConstructor
public class UserProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Column(length = 139)
    private String about;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserProfile(UUID userId, String displayName) {
        this.userId = userId;
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }
}
