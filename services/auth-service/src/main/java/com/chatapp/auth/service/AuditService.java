package com.chatapp.auth.service;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.auth.entity.AuditLog;
import com.chatapp.auth.enums.AuditAction;
import com.chatapp.auth.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

/** Writes the audit trail of sensitive account operations (it never stores passwords or tokens). */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(UUID userId, AuditAction action, String ipAddress) {
        AuditLog entry = new AuditLog();
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setIpAddress(ipAddress);
        entry.setCreatedAt(Instant.now());
        repository.save(entry);
    }
}
