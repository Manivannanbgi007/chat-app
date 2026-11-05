package com.chatapp.call.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * "This user is in that call right now." The user id is the primary key, so one user can hold only one slot:
 * that is how two calls can never take the same person at the same time. Inserted with INSERT IGNORE (see the
 * repository) and deleted when the call reaches a final state.
 */
@Entity
@Table(name = "call_slots")
@Getter
@NoArgsConstructor
public class CallSlot {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "call_id", nullable = false)
    private UUID callId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
