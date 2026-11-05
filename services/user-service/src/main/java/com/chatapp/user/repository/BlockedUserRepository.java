package com.chatapp.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.chatapp.user.entity.BlockedUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlockedUserRepository extends JpaRepository<BlockedUser, UUID> {

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    List<BlockedUser> findByBlockerId(UUID blockerId);

    long deleteByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    /** Of the given users, which ones have blocked the viewer? */
    @Query("select b.blockerId from BlockedUser b where b.blockedId = :viewerId and b.blockerId in :userIds")
    List<UUID> findUsersWhoBlocked(@Param("viewerId") UUID viewerId,
                                   @Param("userIds") Collection<UUID> userIds);

    /** Of the given users, which ones has the blocker blocked? */
    @Query("select b.blockedId from BlockedUser b where b.blockerId = :blockerId and b.blockedId in :userIds")
    List<UUID> findUsersBlockedBy(@Param("blockerId") UUID blockerId,
                                  @Param("userIds") Collection<UUID> userIds);
}
