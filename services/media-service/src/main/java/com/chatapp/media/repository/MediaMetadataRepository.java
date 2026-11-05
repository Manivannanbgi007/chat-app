package com.chatapp.media.repository;

import java.util.UUID;

import com.chatapp.media.entity.MediaMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaMetadataRepository extends JpaRepository<MediaMetadata, UUID> {
}
