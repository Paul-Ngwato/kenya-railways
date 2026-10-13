package com.ueab.sac.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ueab.sac.model.InboxItem;

public interface InboxItemRepository extends JpaRepository<InboxItem, Long> {

    List<InboxItem> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    List<InboxItem> findByOwnerIdAndStatusOrderByCreatedAtAsc(Long ownerId, InboxItem.Status status);

    long countByOwnerIdAndStatus(Long ownerId, InboxItem.Status status);

    /** Used when an event is deleted: references from inbox items are cleared. */
    @Modifying
    @Query("update InboxItem i set i.createdEvent = null, i.duplicateOf = null where i.createdEvent.id = :eventId or i.duplicateOf.id = :eventId")
    void clearEventReferences(@Param("eventId") Long eventId);
}
