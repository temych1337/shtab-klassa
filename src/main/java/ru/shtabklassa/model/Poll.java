package ru.shtabklassa.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// answer в PollAnswer: CONSENT - YES/NO, CHOICE - номер варианта строкой, CUSTOM - сам текст
@Entity
@Table(name = "polls")
public class Poll {

    public static final String YES = "YES";
    public static final String NO = "NO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PollType type;

    @Column(nullable = false, length = 1000)
    private String question;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "poll_options", joinColumns = @JoinColumn(name = "poll_id"))
    @OrderColumn(name = "position")
    @Column(name = "label", nullable = false, length = 200)
    private List<String> options = new ArrayList<>();

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // кнопки "закрыть" пока нет, но закрытый опрос ответы уже не принимает
    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "summary_message_id")
    private String summaryMessageId;

    @Column(name = "last_reminded_at")
    private Instant lastRemindedAt;

    protected Poll() {
    }

    public Poll(PollType type, String question, List<String> options, Long classId, Long authorId, Instant createdAt) {
        this.type = type;
        this.question = question;
        this.options = new ArrayList<>(options);
        this.classId = classId;
        this.authorId = authorId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public PollType getType() {
        return type;
    }

    public String getQuestion() {
        return question;
    }

    public List<String> getOptions() {
        return List.copyOf(options);
    }

    public Long getClassId() {
        return classId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    public void close(Instant at) {
        this.closedAt = at;
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
