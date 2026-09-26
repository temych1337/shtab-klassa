package ru.shtabklassa.service;

import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.model.CalendarEvent;
import ru.shtabklassa.model.EventType;
import ru.shtabklassa.repository.CalendarEventRepository;
import ru.shtabklassa.repository.ReminderLogRepository.TargetKind;
import ru.shtabklassa.support.IntegrationTest;
import ru.shtabklassa.util.SchoolTime;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarServiceTest extends IntegrationTest {

    @Autowired CalendarService calendar;
    @Autowired DutyService dutyService;
    @Autowired CalendarEventRepository events;
    @Autowired CalendarReminderScheduler scheduler;

    private EventInput.Parsed inFuture(Duration in, String title) {
        return new EventInput.Parsed(clock.instant().plus(in).truncatedTo(java.time.temporal.ChronoUnit.MINUTES), title);
    }

    private Set<JobKey> reminderJobs() throws Exception {
        return quartz.getJobKeys(GroupMatcher.jobGroupEquals(CalendarReminderScheduler.GROUP));
    }

    @Test
    void createdEventNotifiesParentsOnceAndSchedulesBothReminders() throws Exception {
        TestClass c = createClass("12a", 3);

        CalendarService.Created created = calendar.createEvent("t-12a", EventType.MEETING,
                inFuture(Duration.ofDays(3), "Родительское собрание"));

        assertThat(created.notified().delivered()).isEqualTo(3);
        assertThat(sender.sentTo("p-12a-1")).singleElement().satisfies(message -> {
            assertThat(message.text()).startsWith("📅 Новое событие\n👥 ").endsWith(" — Родительское собрание");
            assertThat(message.keyboard()).isNull();
        });
        long id = created.event().getId();
        assertThat(reminderJobs()).contains(CalendarReminderScheduler.jobKey(TargetKind.EVENT, id, 1440),
                CalendarReminderScheduler.jobKey(TargetKind.EVENT, id, 60));
        assertThat(c.parents()).hasSize(3);
    }

    @Test
    void eventSoonerThanAnHourGetsNoReminders() throws Exception {
        createClass("12b", 1);

        calendar.createEvent("t-12b", EventType.EVENT, inFuture(Duration.ofMinutes(40), "Линейка"));

        assertThat(reminderJobs()).isEmpty();
    }

    @Test
    void rejectsPastDutyTypeAndNonTeacher() {
        createClass("12c", 1);

        assertThatThrownBy(() -> calendar.createEvent("t-12c", EventType.MEETING,
                new EventInput.Parsed(clock.instant().minusSeconds(60), "Прошло")))
                .isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> calendar.createEvent("t-12c", EventType.DUTY, inFuture(Duration.ofDays(1), "Дежурство")))
                .isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> calendar.createEvent("p-12c-1", EventType.MEETING, inFuture(Duration.ofDays(1), "x")))
                .isInstanceOf(DomainException.NotAllowed.class);
        assertThat(events.count()).isZero();
    }

    @Test
    void agendaMergesEventsAndDutiesByTimeForTeacherAndParent() {
        TestClass c = createClass("12d", 1);
        addStudents(c, "Аня");
        createClass("12e", 1);
        dutyService.planTwoWeeks("t-12d");
        calendar.createEvent("t-12d", EventType.MEETING, inFuture(Duration.ofDays(3), "Собрание"));
        calendar.createEvent("t-12d", EventType.EVENT, inFuture(Duration.ofDays(20), "Слишком далеко"));
        calendar.createEvent("t-12e", EventType.EVENT, inFuture(Duration.ofDays(2), "Чужой класс"));

        Agenda agenda = calendar.agenda("p-12d-1");

        assertThat(agenda.entries()).hasSize(11);
        assertThat(agenda.entries()).isSortedAccordingTo((a, b) -> a.at().compareTo(b.at()));
        assertThat(agenda.render()).contains("👥").contains("Собрание").contains("🧹").contains("дежурит Аня")
                .doesNotContain("Слишком далеко").doesNotContain("Чужой класс");
        assertThat(calendar.agenda("t-12d").entries()).isEqualTo(agenda.entries());
    }

    @Test
    void parentSeesOnlyOwnChildsDutiesAndOnlyByFirstName() {
        TestClass c = createClass("12i", 2);
        addStudents(c, "Михаил Петров", "Анна Смирнова");
        dutyService.planTwoWeeks("t-12i");

        String parentView = calendar.agenda("p-12i-1").render();
        String teacherView = calendar.agenda("t-12i").render();

        assertThat(parentView).contains("дежурит Михаил").doesNotContain("Петров").doesNotContain("Анна");
        assertThat(calendar.agenda("p-12i-1").entries()).hasSize(5);
        assertThat(teacherView).contains("дежурит Михаил Петров").contains("дежурит Анна Смирнова");
        assertThat(calendar.agenda("t-12i").entries()).hasSize(10);
    }

    @Test
    void emptyAgendaSaysSo() {
        createClass("12f", 1);

        assertThat(calendar.agenda("p-12f-1").render()).isEqualTo("📅 Ближайшие 14 дней ничего не запланировано.");
    }

    @Test
    void rescheduleAllRestoresFutureRemindersAfterRestart() throws Exception {
        TestClass c = createClass("12g", 1);
        CalendarEvent future = events.save(new CalendarEvent(c.klass().getId(), EventType.MEETING, "Собрание",
                clock.instant().plus(Duration.ofDays(2)), SchoolTime.DEFAULT_REMINDER_OFFSETS));
        events.save(new CalendarEvent(c.klass().getId(), EventType.MEETING, "Было",
                clock.instant().minus(Duration.ofDays(1)), SchoolTime.DEFAULT_REMINDER_OFFSETS));
        assertThat(reminderJobs()).isEmpty();

        assertThat(scheduler.rescheduleAll()).isEqualTo(2);
        assertThat(scheduler.rescheduleAll()).as("повторный вызов безопасен").isEqualTo(2);

        assertThat(reminderJobs()).containsExactlyInAnyOrder(
                CalendarReminderScheduler.jobKey(TargetKind.EVENT, future.getId(), 1440),
                CalendarReminderScheduler.jobKey(TargetKind.EVENT, future.getId(), 60));
    }

    @Test
    void quartzActuallyFiresTheHourBeforeReminder() {
        createClass("12h", 2);
        Instant start = Instant.now().plus(Duration.ofHours(1)).plusSeconds(2);

        calendar.createEvent("t-12h", EventType.MEETING, new EventInput.Parsed(start, "Собрание через час"));

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(sender.sentTo("p-12h-2")).extracting(message -> message.text())
                        .contains("⏰ Через час, в " + SchoolTime.time(start) + " — Собрание через час"));
    }
}
