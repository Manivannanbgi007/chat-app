package com.chatapp.status.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.status.entity.MediaRelease;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaReleaseRepository extends JpaRepository<MediaRelease, UUID> {

    /** Releases whose (next) attempt is due, oldest first. */
    @Query("select r from MediaRelease r where r.nextAttemptAt <= :now order by r.nextAttemptAt asc")
    List<MediaRelease> findDue(@Param("now") Instant now, Pageable pageable);
}
