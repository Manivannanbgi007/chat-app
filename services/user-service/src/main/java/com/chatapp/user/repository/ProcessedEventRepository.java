package com.chatapp.user.repository;

import java.util.UUID;

import com.chatapp.user.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
