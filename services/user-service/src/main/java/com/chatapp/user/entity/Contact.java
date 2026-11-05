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
import org.hibernate.annotations.UuidGenerator;

/** "ownerId has contactUserId in their contact list". */
@Entity
@Table(name = "contacts")
@Getter
@Setter
@NoArgsConstructor
public class Contact {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "contact_user_id", nullable = false)
    private UUID contactUserId;

    @Column(length = 50)
    private String nickname;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Contact(UUID ownerId, UUID contactUserId, String nickname) {
        this.ownerId = ownerId;
        this.contactUserId = contactUserId;
        this.nickname = nickname;
        this.createdAt = Instant.now();
    }
}
