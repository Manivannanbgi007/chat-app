package com.chatapp.media.repository;

import java.util.UUID;

import com.chatapp.media.entity.MediaAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAccessLogRepository extends JpaRepository<MediaAccessLog, UUID> {
}
