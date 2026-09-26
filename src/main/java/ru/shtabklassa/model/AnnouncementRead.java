package ru.shtabklassa.model;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

// ключ (announcementId, userId) - одна строка на родителя, двойной клик дублей не даст
@Entity
@Table(name = "announcement_reads")
@IdClass(AnnouncementRead.Key.class)
public class AnnouncementRead {

    @Id
    @Column(name = "announcement_id")
    private Long announcementId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    // null = просто прочитал
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Decision decision;

    protected AnnouncementRead() {
    }

    public AnnouncementRead(Long announcementId, Long userId, Instant readAt) {
        this.announcementId = announcementId;
        this.userId = userId;
        this.readAt = readAt;
    }

    public Long getAnnouncementId() {
        return announcementId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
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
