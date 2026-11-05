package com.chatapp.conversation.repository;

import java.util.UUID;

import com.chatapp.conversation.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
