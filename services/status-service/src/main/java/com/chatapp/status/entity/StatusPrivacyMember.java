package com.chatapp.status.entity;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One person on an owner's list: the excepted people (CONTACTS_EXCEPT) or the chosen people (ONLY_SHARE_WITH).
 * The primary key is the pair (ownerId, userId), so nobody can be on the same list twice.
 */
@Entity
@Table(name = "status_privacy_members")
@Getter
@NoArgsConstructor
public class StatusPrivacyMember {

    @EmbeddedId
    private Key id;

    public StatusPrivacyMember(UUID ownerId, UUID userId) {
        this.id = new Key(ownerId, userId);
    }

    public UUID getOwnerId() {
        return id.getOwnerId();
    }

    public UUID getUserId() {
        return id.getUserId();
    }

    /** The two-column primary key. */
    @Embeddable
    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        @Column(name = "owner_id", nullable = false)
        private UUID ownerId;

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        public Key(UUID ownerId, UUID userId) {
            this.ownerId = ownerId;
            this.userId = userId;
        }
    }
}
