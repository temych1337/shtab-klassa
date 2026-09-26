package ru.shtabklassa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.shtabklassa.model.Poll;

import java.time.Instant;

public interface PollRepository extends JpaRepository<Poll, Long> {

    // 0 = в этом окне уже напомнили
    @Transactional
    @Modifying
    @Query("""
            update Poll p set p.lastRemindedAt = :now
            where p.id = :id and (p.lastRemindedAt is null or p.lastRemindedAt <= :notAfter)""")
    int claimReminder(@Param("id") long id, @Param("now") Instant now, @Param("notAfter") Instant notAfter);

    @Transactional
    @Modifying
    @Query("update Poll p set p.lastRemindedAt = :previous where p.id = :id and p.lastRemindedAt = :claimed")
    int releaseReminder(@Param("id") long id, @Param("claimed") Instant claimed, @Param("previous") Instant previous);
}
