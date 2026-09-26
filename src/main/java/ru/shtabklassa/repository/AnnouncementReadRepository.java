package ru.shtabklassa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.shtabklassa.model.AnnouncementRead;
import ru.shtabklassa.model.Decision;

import java.time.Instant;
import java.util.List;

public interface AnnouncementReadRepository extends JpaRepository<AnnouncementRead, AnnouncementRead.Key> {

    // не save(): там select+insert, два одновременных клика оба не найдут строку и второй упадёт на PK.
    // ON CONFLICT атомарный. в H2 работает только с MODE=PostgreSQL!
    @Modifying
    @Query(value = """
            INSERT INTO announcement_reads (announcement_id, user_id, read_at, decision)
            VALUES (:announcementId, :userId, :readAt, CAST(:decision AS VARCHAR(16)))
            ON CONFLICT DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("announcementId") long announcementId, @Param("userId") long userId,
                       @Param("readAt") Instant readAt, @Param("decision") String decision);

    @Modifying
    @Query(value = """
            UPDATE announcement_reads SET decision = CAST(:decision AS VARCHAR(16))
            WHERE announcement_id = :announcementId AND user_id = :userId
              AND decision IS DISTINCT FROM CAST(:decision AS VARCHAR(16))""", nativeQuery = true)
    int updateDecisionIfChanged(@Param("announcementId") long announcementId, @Param("userId") long userId,
                                @Param("decision") String decision);

    long countByAnnouncementId(Long announcementId);

    List<AnnouncementRead> findByAnnouncementId(Long announcementId);

    long countByAnnouncementIdAndDecision(Long announcementId, Decision decision);
}
