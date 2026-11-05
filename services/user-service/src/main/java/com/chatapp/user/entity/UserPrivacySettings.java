package com.chatapp.user.entity;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.user.enums.Visibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_privacy_settings")
@Getter
@Setter
@NoArgsConstructor
public class UserPrivacySettings {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "last_seen", nullable = false, length = 10)
    private Visibility lastSeen;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "profile_photo", nullable = false, length = 10)
    private Visibility profilePhoto;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "about", nullable = false, length = 10)
    private Visibility about;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "group_add", nullable = false, length = 10)
    private Visibility groupAdd;

    @Column(name = "read_receipts", nullable = false)
    private boolean readReceipts;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Creates the defaults: everything visible to everyone, read receipts on. */
    public static UserPrivacySettings defaultsFor(UUID userId) {
        UserPrivacySettings settings = new UserPrivacySettings();
        settings.userId = userId;
        settings.lastSeen = Visibility.EVERYONE;
        settings.profilePhoto = Visibility.EVERYONE;
        settings.about = Visibility.EVERYONE;
        settings.groupAdd = Visibility.EVERYONE;
        settings.readReceipts = true;
        settings.updatedAt = Instant.now();
        return settings;
    }
}
