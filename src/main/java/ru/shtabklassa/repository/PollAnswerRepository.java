package ru.shtabklassa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.shtabklassa.model.PollAnswer;

import java.time.Instant;
import java.util.List;

public interface PollAnswerRepository extends JpaRepository<PollAnswer, PollAnswer.Key> {

    // как в announcement_reads
    @Modifying
    @Query(value = """
            INSERT INTO poll_answers (poll_id, user_id, answer, answered_at)
            VALUES (:pollId, :userId, :answer, :answeredAt)
            ON CONFLICT DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("pollId") long pollId, @Param("userId") long userId,
                       @Param("answer") String answer, @Param("answeredAt") Instant answeredAt);

    @Modifying
    @Query(value = """
            UPDATE poll_answers SET answer = :answer, answered_at = :answeredAt
            WHERE poll_id = :pollId AND user_id = :userId AND answer <> :answer""", nativeQuery = true)
    int updateIfChanged(@Param("pollId") long pollId, @Param("userId") long userId,
                        @Param("answer") String answer, @Param("answeredAt") Instant answeredAt);

    long countByPollId(Long pollId);

    List<PollAnswer> findByPollId(Long pollId);

    @Query("select a.answer, count(a) from PollAnswer a where a.pollId = :pollId group by a.answer")
    List<Object[]> countByAnswer(@Param("pollId") Long pollId);

    List<PollAnswer> findTop3ByPollIdOrderByAnsweredAtDesc(Long pollId);
}
