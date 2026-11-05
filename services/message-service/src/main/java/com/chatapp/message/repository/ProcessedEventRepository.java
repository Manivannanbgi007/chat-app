package com.chatapp.message.repository;

import java.util.UUID;

import com.chatapp.message.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
