package com.chatapp.message.client;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Asks conversation-service (the source of truth) who is in a conversation. */
public interface ConversationClient {

    /** Empty when the conversation does not exist. Throws ServiceUnavailableException when the service is down. */
    Optional<ConversationInfo> find(UUID conversationId);

    /**
     * conversation-service's answer (GET /internal/conversations/{id}):
     *  - participantIds: the CURRENT members
     *  - adminIds, onlyAdminsCanSend: group rules for sending
     *  - members: EVERY membership period, current and past (leftAt null = still a member)
     * Missing lists become empty lists, so the rest of the code never sees null.
     */
    record ConversationInfo(UUID id, String type, List<UUID> participantIds, List<UUID> adminIds,
                            Boolean onlyAdminsCanSend, List<MemberPeriod> members) {

        public ConversationInfo {
            participantIds = participantIds == null ? List.of() : List.copyOf(participantIds);
            adminIds = adminIds == null ? List.of() : List.copyOf(adminIds);
            members = members == null ? List.of() : members.stream().filter(p -> p.userId() != null && p.joinedAt() != null)
                    .map(MemberPeriod::truncated).toList();
        }

        public boolean isGroup() {
            return "GROUP".equals(type);
        }

        public String typeOrPrivate() {
            return isGroup() ? "GROUP" : "PRIVATE";
        }

        public boolean isCurrentMember(UUID userId) {
            return participantIds.contains(userId);
        }

        /** True for current members and for former members (anyone who had a period). */
        public boolean hasEverBeenMember(UUID userId) {
            return isCurrentMember(userId) || members.stream().anyMatch(period -> period.userId().equals(userId));
        }

        public boolean onlyAdminsMaySend() {
            return isGroup() && Boolean.TRUE.equals(onlyAdminsCanSend);
        }

        public boolean isAdmin(UUID userId) {
            return adminIds.contains(userId);
        }
    }

    /** One membership period. Times are cut to microseconds, like the database columns. */
    record MemberPeriod(UUID userId, Instant joinedAt, Instant leftAt) {

        MemberPeriod truncated() {
            return new MemberPeriod(userId, joinedAt.truncatedTo(ChronoUnit.MICROS),
                    leftAt == null ? null : leftAt.truncatedTo(ChronoUnit.MICROS));
        }
    }
}
