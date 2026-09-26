package ru.shtabklassa.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "calendar_events")
public class CalendarEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventType type;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    // в минутах: 1440 за день, 60 за час
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "calendar_event_reminder_offsets", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "offset_minutes", nullable = false)
    private Set<Integer> reminderOffsets = new HashSet<>();

    protected CalendarEvent() {
    }

    public CalendarEvent(Long classId, EventType type, String title, Instant startAt, Set<Integer> reminderOffsets) {
        this.classId = classId;
        this.type = type;
        this.title = title;
        this.startAt = startAt;
        this.reminderOffsets = new HashSet<>(reminderOffsets);
    }

    public Long getId() {
        return id;
    }

    public Long getClassId() {
        return classId;
    }

    public EventType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Set<Integer> getReminderOffsets() {
        return Set.copyOf(reminderOffsets);
    }
}
