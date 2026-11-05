package com.chatapp.media.entity;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.media.enums.AccessAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/** Who did what with which file (upload, link handed out, attach, delete). Never stores file contents. */
@Entity
@Table(name = "media_access_logs")
@Getter
@Setter
@NoArgsConstructor
public class MediaAccessLog {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccessAction action;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
