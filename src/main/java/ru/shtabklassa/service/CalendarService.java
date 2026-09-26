package ru.shtabklassa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.shtabklassa.model.CalendarEvent;
import ru.shtabklassa.model.EventType;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.CalendarEventRepository;
import ru.shtabklassa.repository.UserRepository;
import ru.shtabklassa.util.SchoolTime;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class CalendarService {

    private static final Logger log = LoggerFactory.getLogger(CalendarService.class);

    static final int AGENDA_DAYS = 14;

    public record Created(CalendarEvent event, ParentBroadcaster.Tally notified) {
    }

    private final CalendarEventRepository events;
    private final UserRepository users;
    private final DutyService duties;
    private final CalendarReminderScheduler reminders;
    private final ParentBroadcaster broadcaster;
    private final Clock clock;

    public CalendarService(CalendarEventRepository events, UserRepository users, DutyService duties,
                           CalendarReminderScheduler reminders, ParentBroadcaster broadcaster, Clock clock) {
        this.events = events;
        this.users = users;
        this.duties = duties;
        this.reminders = reminders;
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    public static String icon(EventType type) {
        return switch (type) {
            case MEETING -> "👥";
            case EVENT -> "🎉";
            case DUTY -> "🧹";
        };
    }

    // + напоминания за день и за час, родителям одно уведомление сразу. DUTY сюда нельзя, это DutyService
    public Created createEvent(String teacherExternalId, EventType type, EventInput.Parsed event) {
        User teacher = users.findByExternalId(teacherExternalId).orElseThrow(DomainException.UnknownUser::new);
        if (teacher.getRole() != Role.TEACHER) {
            throw new DomainException.NotAllowed("События создаёт классный руководитель.");
        }
        if (teacher.getClassId() == null) {
            throw new DomainException.NotAllowed("Вы не привязаны к классу.");
        }
        if (type == EventType.DUTY) {
            throw new DomainException.InvalidInput("Дежурства составляются через «🧹 Дежурства».");
        }
        // черновик мог провисеть
        if (!event.startAt().isAfter(clock.instant())) {
            throw new DomainException.InvalidInput("Это время уже прошло.");
        }

        CalendarEvent saved = events.save(new CalendarEvent(teacher.getClassId(), type, event.title(), event.startAt(),
                SchoolTime.DEFAULT_REMINDER_OFFSETS));
        reminders.scheduleEvent(saved);

        List<User> parents = users.findByClassIdAndRoleOrderByFullName(teacher.getClassId(), Role.PARENT);
        String text = "📅 Новое событие\n" + icon(type) + " " + SchoolTime.dayAndTime(saved.getStartAt())
                + " — " + saved.getTitle();
        ParentBroadcaster.Tally tally = ParentBroadcaster.Tally.of(broadcaster.send(parents, text, null));
        if (tally.failed() > 0) {
            log.warn("событие {}: уведомление не доставлено {} из {}", saved.getId(), tally.failed(), parents.size());
        }
        return new Created(saved, tally);
    }

    // учитель видит все дежурства, родитель только своего ребёнка
    public Agenda agenda(String externalId) {
        User user = users.findByExternalId(externalId).orElseThrow(DomainException.UnknownUser::new);
        if (user.getClassId() == null) {
            throw new DomainException.NotAllowed("Вы не привязаны к классу.");
        }
        Instant now = clock.instant();
        LocalDate today = SchoolTime.today(clock);
        LocalDate lastDay = today.plusDays(AGENDA_DAYS);

        List<Agenda.Entry> entries = new ArrayList<>();
        for (CalendarEvent event : events.findByClassIdAndStartAtBetweenOrderByStartAt(
                user.getClassId(), now, lastDay.plusDays(1).atStartOfDay(SchoolTime.ZONE).toInstant())) {
            entries.add(new Agenda.Entry(event.getStartAt(), icon(event.getType()) + " "
                    + SchoolTime.dayAndTime(event.getStartAt()) + " — " + event.getTitle()));
        }
        entries.addAll(duties.agendaEntries(user.getClassId(), today, lastDay,
                user.getRole() == Role.TEACHER ? null : user.getId()));
        entries.sort(Comparator.comparing(Agenda.Entry::at));
        return new Agenda(AGENDA_DAYS, entries);
    }
}
