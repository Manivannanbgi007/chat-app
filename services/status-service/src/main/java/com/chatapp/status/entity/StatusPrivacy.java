package com.chatapp.status.entity;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.status.enums.PrivacyMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The status privacy mode of one owner. No row means the default: CONTACTS. */
@Entity
@Table(name = "status_privacy")
@Getter
@Setter
@NoArgsConstructor
public class StatusPrivacy {

    @Id
    @Column(name = "owner_id")
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PrivacyMode mode;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public StatusPrivacy(UUID ownerId, PrivacyMode mode, Instant updatedAt) {
        this.ownerId = ownerId;
        this.mode = mode;
        this.updatedAt = updatedAt;
    }
}
