package ru.shtabklassa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.shtabklassa.model.CalendarEvent;

import java.time.Instant;
import java.util.List;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    List<CalendarEvent> findByClassIdAndStartAtBetweenOrderByStartAt(Long classId, Instant from, Instant to);

    List<CalendarEvent> findByStartAtAfter(Instant after);
}
