package ru.shtabklassa.model;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "poll_deliveries")
@IdClass(PollDelivery.Key.class)
public class PollDelivery {

    @Id
    @Column(name = "poll_id")
    private Long pollId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "message_id")
    private String messageId;

    @Column(name = "failed_reason", length = 500)
    private String failedReason;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected PollDelivery() {
    }

    public PollDelivery(Long pollId, Long userId, String messageId, String failedReason, Instant sentAt) {
        this.pollId = pollId;
        this.userId = userId;
        this.messageId = messageId;
        this.failedReason = failedReason == null || failedReason.length() <= 500 ? failedReason : failedReason.substring(0, 500);
        this.sentAt = sentAt;
    }

    public Long getPollId() {
        return pollId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getFailedReason() {
        return failedReason;
    }

    public Instant getSentAt() {
        return sentAt;
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
