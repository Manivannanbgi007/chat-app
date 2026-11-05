package com.chatapp.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.user.entity.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    boolean existsByOwnerIdAndContactUserId(UUID ownerId, UUID contactUserId);

    List<Contact> findByOwnerId(UUID ownerId);

    Optional<Contact> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * Everybody who has saved :viewerId in THEIR contact list, except people who blocked the viewer or whom the
     * viewer blocked. One query, so it stays fast even when many people saved the viewer.
     * (Used for statuses: these are the only people whose status the viewer can possibly see.)
     */
    @Query("""
            select c.ownerId from Contact c
            where c.contactUserId = :viewerId
              and c.ownerId <> :viewerId
              and not exists (select b.id from BlockedUser b
                              where (b.blockerId = c.ownerId and b.blockedId = :viewerId)
                                 or (b.blockerId = :viewerId and b.blockedId = c.ownerId))
            order by c.createdAt, c.ownerId""")
    List<UUID> findOwnersWithContactNotBlocked(@Param("viewerId") UUID viewerId);

    /** Of the given owners, which ones have the viewer in their contact list? (used for CONTACTS privacy) */
    @Query("select c.ownerId from Contact c where c.contactUserId = :viewerId and c.ownerId in :ownerIds")
    List<UUID> findOwnersWhoHaveContact(@Param("viewerId") UUID viewerId,
                                        @Param("ownerIds") Collection<UUID> ownerIds);
}
