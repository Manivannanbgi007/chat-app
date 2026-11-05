package com.chatapp.conversation.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Makes sure two users can only ever have ONE private conversation.
 * The smaller user id is always user_low_id, so the pair (A,B) is the same as (B,A),
 * and the database unique key rejects a second conversation for the same pair.
 */
@Entity
@Table(name = "private_conversation_keys")
@Getter
@NoArgsConstructor
public class PrivateConversationKey {

    @Id
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "user_low_id", nullable = false)
    private UUID userLowId;

    @Column(name = "user_high_id", nullable = false)
    private UUID userHighId;

    public PrivateConversationKey(UUID conversationId, UUID userA, UUID userB) {
        this.conversationId = conversationId;
        this.userLowId = lowerOf(userA, userB);
        this.userHighId = higherOf(userA, userB);
    }

    public static UUID lowerOf(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static UUID higherOf(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? b : a;
    }
}
