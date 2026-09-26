package ru.shtabklassa.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "announcements")
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(nullable = false, length = 4000)
    private String text;

    // true = "Согласен / Не смогу", false = просто "Прочитал"
    @Column(name = "requires_decision", nullable = false)
    private boolean requiresDecision;

    private Instant deadline;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "attachment_url", length = 1000)
    private String attachmentUrl;

    @Column(name = "summary_message_id")
    private String summaryMessageId;

    @Column(name = "last_reminded_at")
    private Instant lastRemindedAt;

    protected Announcement() {
    }

    public Announcement(Long classId, Long authorId, String text, boolean requiresDecision, Instant createdAt) {
        this.classId = classId;
        this.authorId = authorId;
        this.text = text;
        this.requiresDecision = requiresDecision;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getClassId() {
        return classId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public String getText() {
        return text;
    }

    public boolean isRequiresDecision() {
        return requiresDecision;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getAttachmentUrl() {
        return attachmentUrl;
    }

    public void setAttachmentUrl(String attachmentUrl) {
        this.attachmentUrl = attachmentUrl;
    }

    public String getSummaryMessageId() {
        return summaryMessageId;
    }

    public void setSummaryMessageId(String summaryMessageId) {
        this.summaryMessageId = summaryMessageId;
    }

    public Instant getLastRemindedAt() {
        return lastRemindedAt;
    }
}
