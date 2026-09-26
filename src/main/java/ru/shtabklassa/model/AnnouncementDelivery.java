package ru.shtabklassa.model;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

// либо messageId, либо failedReason
@Entity
@Table(name = "announcement_deliveries")
@IdClass(AnnouncementDelivery.Key.class)
public class AnnouncementDelivery {

    @Id
    @Column(name = "announcement_id")
    private Long announcementId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "message_id")
    private String messageId;

    @Column(name = "failed_reason", length = 500)
    private String failedReason;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected AnnouncementDelivery() {
    }

    public static AnnouncementDelivery delivered(Long announcementId, Long userId, String messageId, Instant at) {
        AnnouncementDelivery delivery = new AnnouncementDelivery(announcementId, userId, at);
        delivery.messageId = messageId;
        return delivery;
    }

    public static AnnouncementDelivery failed(Long announcementId, Long userId, String reason, Instant at) {
        AnnouncementDelivery delivery = new AnnouncementDelivery(announcementId, userId, at);
        delivery.failedReason = reason.length() > 500 ? reason.substring(0, 500) : reason;
        return delivery;
    }

    private AnnouncementDelivery(Long announcementId, Long userId, Instant sentAt) {
        this.announcementId = announcementId;
        this.userId = userId;
        this.sentAt = sentAt;
    }

    public Long getAnnouncementId() {
        return announcementId;
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
        private Long announcementId;
        private Long userId;

        protected Key() {
        }

        public Key(Long announcementId, Long userId) {
            this.announcementId = announcementId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(announcementId, other.announcementId)
                    && Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(announcementId, userId);
        }
    }
}
