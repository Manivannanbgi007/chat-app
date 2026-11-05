package com.chatapp.conversation.repository;

import java.util.UUID;

import com.chatapp.conversation.entity.GroupSettings;
import org.springframework.data.jpa.repository.JpaRepository;

/** The id of a GroupSettings row is the conversation id. */
public interface GroupSettingsRepository extends JpaRepository<GroupSettings, UUID> {
}
