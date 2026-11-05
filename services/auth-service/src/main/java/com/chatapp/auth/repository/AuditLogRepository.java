package com.chatapp.auth.repository;

import java.util.UUID;

import com.chatapp.auth.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
}
