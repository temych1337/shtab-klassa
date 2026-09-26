package ru.shtabklassa.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

// без entity, строку никто не читает - она только застолбляет отправку
@Repository
public class ReminderLogRepository {

    public enum TargetKind {
        EVENT,
        DUTY
    }

    private final JdbcTemplate jdbc;

    public ReminderLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claim(TargetKind kind, long targetId, int offsetMinutes, Instant at) {
        return jdbc.update("""
                INSERT INTO calendar_reminder_log (target_kind, target_id, offset_minutes, sent_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING""", kind.name(), targetId, offsetMinutes, Timestamp.from(at)) == 1;
    }
}
