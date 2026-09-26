package ru.shtabklassa.model;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "poll_answers")
@IdClass(PollAnswer.Key.class)
public class PollAnswer {

    @Id
    @Column(name = "poll_id")
    private Long pollId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 1000)
    private String answer;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    protected PollAnswer() {
    }

    public PollAnswer(Long pollId, Long userId, String answer, Instant answeredAt) {
        this.pollId = pollId;
        this.userId = userId;
        this.answer = answer;
        this.answeredAt = answeredAt;
    }

    public Long getPollId() {
        return pollId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAnswer() {
        return answer;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public void changeAnswer(String answer, Instant at) {
        this.answer = answer;
        this.answeredAt = at;
    }

    public static class Key implements Serializable {
        private Long pollId;
        private Long userId;

        protected Key() {
        }

        public Key(Long pollId, Long userId) {
            this.pollId = pollId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(pollId, other.pollId)
                    && Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pollId, userId);
        }
    }
}
