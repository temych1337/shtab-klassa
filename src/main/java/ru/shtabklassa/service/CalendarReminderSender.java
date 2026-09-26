package ru.shtabklassa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.shtabklassa.adapter.MessageDeliveryException;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.model.CalendarEvent;
import ru.shtabklassa.model.DutySchedule;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.CalendarEventRepository;
import ru.shtabklassa.repository.DutyScheduleRepository;
import ru.shtabklassa.repository.ReminderLogRepository;
import ru.shtabklassa.repository.ReminderLogRepository.TargetKind;
import ru.shtabklassa.repository.UserRepository;
import ru.shtabklassa.util.SchoolTime;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

// перед отправкой пишем в calendar_reminder_log, так что максимум один раз.
// минус: упала сеть сразу после - напоминание пропало. лучше так, чем дубль
@Service
public class CalendarReminderSender {

    private static final Logger log = LoggerFactory.getLogger(CalendarReminderSender.class);

    private final CalendarEventRepository events;
    private final DutyScheduleRepository duties;
    private final UserRepository users;
    private final ReminderLogRepository reminderLog;
    private final ParentBroadcaster broadcaster;
    private final MessageSender sender;
    private final Clock clock;

    public CalendarReminderSender(CalendarEventRepository events, DutyScheduleRepository duties, UserRepository users,
                                  ReminderLogRepository reminderLog, ParentBroadcaster broadcaster,
                                  MessageSender sender, Clock clock) {
        this.events = events;
        this.duties = duties;
        this.users = users;
        this.reminderLog = reminderLog;
        this.broadcaster = broadcaster;
        this.sender = sender;
        this.clock = clock;
    }

    public void sendEventReminder(long eventId, int offsetMinutes) {
        CalendarEvent event = events.findById(eventId).orElse(null);
        if (event == null) {
            log.info("напоминание о событии {}: события уже нет", eventId);
            return;
        }
        if (!reminderLog.claim(TargetKind.EVENT, eventId, offsetMinutes, clock.instant())) {
            return;
        }
        List<User> parents = users.findByClassIdAndRoleOrderByFullName(event.getClassId(), Role.PARENT);
        String text = "⏰ " + when(offsetMinutes, event.getStartAt()) + " — " + event.getTitle();
        ParentBroadcaster.Tally tally = ParentBroadcaster.Tally.of(broadcaster.send(parents, text, null));
        log.info("напоминание о событии {} (за {} мин): доставлено {}, не доставлено {}, демо {}",
                eventId, offsetMinutes, tally.delivered(), tally.failed(), tally.demo());
    }

    private static String when(int offsetMinutes, Instant startAt) {
        return switch (offsetMinutes) {
            case 1440 -> "Завтра в " + SchoolTime.time(startAt);
            case 60 -> "Через час, в " + SchoolTime.time(startAt);
            default -> SchoolTime.dayAndTime(startAt);
        };
    }

    // шлём родителю дежурного. нет родителя/аккаунта - только warn в лог
    public void sendDutyReminder(long dutyId, int offsetMinutes) {
        DutySchedule duty = duties.findById(dutyId).orElse(null);
        if (duty == null) {
            log.info("напоминание о дежурстве {}: записи уже нет", dutyId);
            return;
        }
        User student = users.findById(duty.getStudentId()).orElse(null);
        User parent = student == null || student.getParentId() == null ? null : users.findById(student.getParentId()).orElse(null);
        if (parent == null || parent.getExternalId() == null) {
            log.warn("дежурство {} ({}): у ученика нет родителя с аккаунтом, пропускаю",
                    dutyId, duty.getDate());
            return;
        }
        if (!reminderLog.claim(TargetKind.DUTY, dutyId, offsetMinutes, clock.instant())) {
            return;
        }
        String text = offsetMinutes == 60
                ? "🧹 Сегодня дежурит " + student.getFullName() + ", начало в " + SchoolTime.DUTY_START + "."
                : "🧹 Завтра (" + SchoolTime.day(duty.getDate()) + ") дежурит " + student.getFullName()
                  + ". Начало в " + SchoolTime.DUTY_START + ".";
        try {
            sender.sendMessage(parent.getExternalId(), text);
        } catch (MessageDeliveryException e) {
            log.warn("напоминание о дежурстве {} не доставлено: {}", dutyId, e.getMessage());
        }
    }
}
