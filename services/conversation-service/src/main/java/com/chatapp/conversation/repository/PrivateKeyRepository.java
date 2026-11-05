package com.chatapp.conversation.repository;

import java.util.Optional;
import java.util.UUID;

import com.chatapp.conversation.entity.PrivateConversationKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivateKeyRepository extends JpaRepository<PrivateConversationKey, UUID> {

    Optional<PrivateConversationKey> findByUserLowIdAndUserHighId(UUID userLowId, UUID userHighId);
}
