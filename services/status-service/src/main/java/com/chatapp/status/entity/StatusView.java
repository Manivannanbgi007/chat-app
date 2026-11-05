package com.chatapp.status.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** "viewerId saw statusId at viewedAt". One row per (status, viewer); written with INSERT IGNORE. */
@Entity
@Table(name = "status_views")
@Getter
@NoArgsConstructor
public class StatusView {

    @Id
    private UUID id;

    @Column(name = "status_id", nullable = false)
    private UUID statusId;

    @Column(name = "viewer_id", nullable = false)
    private UUID viewerId;

    @Column(name = "viewed_at", nullable = false)
    private Instant viewedAt;
}
