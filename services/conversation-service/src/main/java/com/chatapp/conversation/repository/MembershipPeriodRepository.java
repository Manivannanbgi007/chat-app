package com.chatapp.conversation.repository;

import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.entity.MembershipPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipPeriodRepository extends JpaRepository<MembershipPeriod, UUID> {

    /** Every period of a conversation (current and past), oldest first. */
    List<MembershipPeriod> findByConversationIdOrderByJoinedAtAscUserIdAsc(UUID conversationId);

    /** The open period(s) of a user: normally exactly one while they are a member, none after they left. */
    List<MembershipPeriod> findByConversationIdAndUserIdAndLeftAtIsNull(UUID conversationId, UUID userId);
}
