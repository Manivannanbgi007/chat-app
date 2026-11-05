package com.chatapp.media.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.media.entity.MediaFile;
import com.chatapp.media.enums.MediaStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {

    /**
     * Marks a file as used by a message, but ONLY if nobody used it before. The database decides who wins when
     * two requests arrive at the same moment: exactly one of them changes the row (returns 1), the other gets 0.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaFile m set m.status = com.chatapp.media.enums.MediaStatus.ATTACHED,
                   m.attachReference = :reference, m.attachedAt = :now
            where m.id = :id and m.status = com.chatapp.media.enums.MediaStatus.UPLOADED""")
    int attachIfUnused(@Param("id") UUID id, @Param("reference") String reference, @Param("now") Instant now);

    /**
     * Marks a file as deleted, but ONLY if no message uses it (yet). Same idea as attachIfUnused: the database
     * decides if an upload that is deleted at the same moment as it is attached ends up deleted or attached.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaFile m set m.status = com.chatapp.media.enums.MediaStatus.DELETED, m.deletedAt = :now
            where m.id = :id and m.status = com.chatapp.media.enums.MediaStatus.UPLOADED""")
    int deleteIfUnused(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Marks an ATTACHED file as deleted, but ONLY if it belongs to :ownerId and was attached with exactly
     * :reference (status-service releasing the file of an expired status). A file attached by a message has
     * another reference, so it can never match. Two releases at the same moment: one changes the row, the other gets 0.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaFile m set m.status = com.chatapp.media.enums.MediaStatus.DELETED, m.deletedAt = :now
            where m.id = :id and m.ownerId = :ownerId and m.attachReference = :reference
              and m.status = com.chatapp.media.enums.MediaStatus.ATTACHED""")
    int releaseIfAttachedWith(@Param("id") UUID id, @Param("ownerId") UUID ownerId,
                              @Param("reference") String reference, @Param("now") Instant now);

    /**
     * Reads the CURRENT row with a lock ("select ... for update"). A normal read inside a transaction can show an
     * older picture of the table; after losing a race we must look at what the winner really wrote.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MediaFile m where m.id = :id")
    Optional<MediaFile> findForUpdate(@Param("id") UUID id);

    /** Files with the given status that are older than the given time (oldest first). */
    @Query("select m from MediaFile m where m.status = :status and m.createdAt < :before order by m.createdAt")
    List<MediaFile> findOlderThan(@Param("status") MediaStatus status, @Param("before") Instant before, Pageable pageable);
}
