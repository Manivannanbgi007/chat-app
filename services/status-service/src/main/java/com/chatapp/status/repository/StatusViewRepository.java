package com.chatapp.status.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.chatapp.status.entity.StatusView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface StatusViewRepository extends JpaRepository<StatusView, UUID> {

    /** Records a view. INSERT IGNORE: viewing again (or two requests at once) keeps the first view and is no error. */
    @Modifying
    @Transactional
    @Query(value = "INSERT IGNORE INTO status_views (id, status_id, viewer_id, viewed_at) "
            + "VALUES (:id, :statusId, :viewerId, :viewedAt)", nativeQuery = true)
    int insertIgnore(@Param("id") String id, @Param("statusId") String statusId, @Param("viewerId") String viewerId,
                     @Param("viewedAt") Instant viewedAt);

    /** Of the given statuses: the ones this viewer has seen. */
    @Query("select v.statusId from StatusView v where v.viewerId = :viewerId and v.statusId in :statusIds")
    List<UUID> findViewedStatusIds(@Param("viewerId") UUID viewerId, @Param("statusIds") Collection<UUID> statusIds);

    /** Every view of the given statuses (to count and list viewers). */
    List<StatusView> findByStatusIdIn(Collection<UUID> statusIds);

    /** The views of one status, newest first. */
    List<StatusView> findByStatusIdOrderByViewedAtDesc(UUID statusId);
}
