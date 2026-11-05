package com.chatapp.conversation.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Name, description and rules of a group. One row per GROUP conversation (same id as the conversation). */
@Entity
@Table(name = "group_settings")
@Getter
@Setter
@NoArgsConstructor
public class GroupSettings {

    @Id
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /** Group picture. Stays null until media-service exists (Phase 5). */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** Only admins may send messages. */
    @Column(name = "only_admins_can_send", nullable = false)
    private boolean onlyAdminsCanSend;

    /** Only admins may change the name and the description. */
    @Column(name = "only_admins_can_edit", nullable = false)
    private boolean onlyAdminsCanEdit;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public GroupSettings(UUID conversationId, String name, String description, Instant now) {
        this.conversationId = conversationId;
        this.name = name;
        this.description = description;
        this.updatedAt = now;
    }
}
