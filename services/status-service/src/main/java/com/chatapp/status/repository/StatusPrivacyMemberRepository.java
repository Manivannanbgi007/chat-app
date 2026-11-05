package com.chatapp.status.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.chatapp.status.entity.StatusPrivacyMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StatusPrivacyMemberRepository extends JpaRepository<StatusPrivacyMember, StatusPrivacyMember.Key> {

    /** The people on one owner's list. */
    @Query("select m.id.userId from StatusPrivacyMember m where m.id.ownerId = :ownerId")
    List<UUID> findUserIds(@Param("ownerId") UUID ownerId);

    /** Is this user on the owner's list? */
    @Query("select count(m) > 0 from StatusPrivacyMember m where m.id.ownerId = :ownerId and m.id.userId = :userId")
    boolean isListed(@Param("ownerId") UUID ownerId, @Param("userId") UUID userId);

    /** Of the given owners: the ones that have this user on their list (one query for the whole feed). */
    @Query("select m.id.ownerId from StatusPrivacyMember m where m.id.userId = :userId and m.id.ownerId in :ownerIds")
    List<UUID> findOwnersListing(@Param("userId") UUID userId, @Param("ownerIds") Collection<UUID> ownerIds);

    @Modifying
    @Query("delete from StatusPrivacyMember m where m.id.ownerId = :ownerId")
    int deleteByOwner(@Param("ownerId") UUID ownerId);
}
