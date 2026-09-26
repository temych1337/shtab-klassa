package ru.shtabklassa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.shtabklassa.model.Announcement;

import java.time.Instant;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    // как в PollRepository
    @Transactional
    @Modifying
    @Query("""
            update Announcement a set a.lastRemindedAt = :now
            where a.id = :id and (a.lastRemindedAt is null or a.lastRemindedAt <= :notAfter)""")
    int claimReminder(@Param("id") long id, @Param("now") Instant now, @Param("notAfter") Instant notAfter);

    @Transactional
    @Modifying
    @Query("update Announcement a set a.lastRemindedAt = :previous where a.id = :id and a.lastRemindedAt = :claimed")
    int releaseReminder(@Param("id") long id, @Param("claimed") Instant claimed, @Param("previous") Instant previous);
}
