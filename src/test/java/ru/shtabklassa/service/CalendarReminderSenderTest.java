package ru.shtabklassa.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.model.CalendarEvent;
import ru.shtabklassa.model.DutySchedule;
import ru.shtabklassa.model.EventType;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.CalendarEventRepository;
import ru.shtabklassa.repository.DutyScheduleRepository;
import ru.shtabklassa.support.FakeMessageSender;
import ru.shtabklassa.support.IntegrationTest;
import ru.shtabklassa.util.SchoolTime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarReminderSenderTest extends IntegrationTest {

    @Autowired CalendarReminderSender reminders;
    @Autowired CalendarEventRepository events;
    @Autowired DutyScheduleRepository duties;

    private CalendarEvent event(TestClass c, String title) {
        return events.save(new CalendarEvent(c.klass().getId(), EventType.MEETING, title,
                Instant.parse("2026-10-01T15:30:00Z"), SchoolTime.DEFAULT_REMINDER_OFFSETS));
    }

    @Test
    void eventReminderGoesToAllParentsExactlyOnce() {
        TestClass c = createClass("13a", 3);
        CalendarEvent meeting = event(c, "Родительское собрание");

        reminders.sendEventReminder(meeting.getId(), 60);
        reminders.sendEventReminder(meeting.getId(), 60);

        assertThat(sender.sent).hasSize(3);
        assertThat(sender.sentTo("p-13a-2")).singleElement().extracting(FakeMessageSender.Sent::text)
                .isEqualTo("⏰ Через час, в 18:30 — Родительское собрание");
    }

    @Test
    void dayBeforeWording() {
        TestClass c = createClass("13b", 1);
        CalendarEvent meeting = event(c, "Собрание");

        reminders.sendEventReminder(meeting.getId(), 1440);

        assertThat(sender.sentTo("p-13b-1").getFirst().text()).isEqualTo("⏰ Завтра в 18:30 — Собрание");
    }

    @Test
    void deletedEventSendsNothing() {
        TestClass c = createClass("13c", 1);
        CalendarEvent meeting = event(c, "Отменили");
        events.delete(meeting);

        reminders.sendEventReminder(meeting.getId(), 60);

        assertThat(sender.sent).isEmpty();
    }

    @Test
    void dutyReminderGoesToStudentsParentOnce() {
        TestClass c = createClass("13d", 2);
        List<User> students = addStudents(c, "Миша Петров", "Катя Смирнова");
        DutySchedule duty = duties.save(new DutySchedule(c.klass().getId(), students.get(1).getId(),
                LocalDate.of(2026, 10, 2), 1));

        reminders.sendDutyReminder(duty.getId(), 1440);
        reminders.sendDutyReminder(duty.getId(), 1440);
        reminders.sendDutyReminder(duty.getId(), 60);

        assertThat(sender.sentTo("p-13d-1")).isEmpty();
        assertThat(sender.sentTo("p-13d-2")).extracting(FakeMessageSender.Sent::text).containsExactly(
                "🧹 Завтра (пт 02.10) дежурит Катя Смирнова. Начало в 08:00.",
                "🧹 Сегодня дежурит Катя Смирнова, начало в 08:00.");
    }

    @Test
    void dutyWithoutParentIsSkippedAndNotBurned() {
        TestClass c = createClass("13e", 0);
        User orphan = addStudents(c, "Без родителя").getFirst();
        DutySchedule duty = duties.save(new DutySchedule(c.klass().getId(), orphan.getId(), LocalDate.of(2026, 10, 2), 0));

        reminders.sendDutyReminder(duty.getId(), 60);
        assertThat(sender.sent).isEmpty();

        // родителя привязали позже - пропуск не "сжёг" напоминание
        User parent = users.save(new User("late-parent", ru.shtabklassa.model.Role.PARENT, c.klass().getId(), "Мама"));
        orphan.setParentId(parent.getId());
        users.save(orphan);
        reminders.sendDutyReminder(duty.getId(), 60);
        assertThat(sender.sentTo("late-parent")).hasSize(1);
    }
}
