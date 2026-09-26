package ru.shtabklassa.bot.handler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.adapter.Keyboard;
import ru.shtabklassa.bot.keyboard.Keyboards;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.model.EventType;
import ru.shtabklassa.model.PollType;
import ru.shtabklassa.repository.CalendarEventRepository;
import ru.shtabklassa.repository.DutyScheduleRepository;
import ru.shtabklassa.repository.PollAnswerRepository;
import ru.shtabklassa.service.EventInput;
import ru.shtabklassa.service.PollService;
import ru.shtabklassa.util.SchoolTime;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.shtabklassa.bot.keyboard.Payload.Action.*;

class CalendarDialogTest extends BotDialogTest {

    @Autowired CalendarEventRepository events;
    @Autowired DutyScheduleRepository duties;
    @Autowired PollService pollService;
    @Autowired PollAnswerRepository answers;

    private String inThreeDays(String rest) {
        return clock.instant().plus(Duration.ofDays(3)).atZone(SchoolTime.ZONE)
                .format(DateTimeFormatter.ofPattern("dd.MM")) + " " + rest;
    }

    @Test
    void teacherMenuHasCalendarAndDuties() {
        createClass("14a", 1);

        text("t-14a", "привет");

        assertThat(sender.sentTo("t-14a").getLast().keyboard().rows()).hasSize(2);
        assertThat(sender.sentTo("t-14a").getLast().keyboard().rows().get(1)).extracting(Keyboard.Button::label)
                .containsExactly(Keyboards.CALENDAR_LABEL, Keyboards.DUTIES_LABEL);
    }

    @Test
    void teacherCreatesMeetingWithStrictDateInput() {
        createClass("14b", 2);

        text("t-14b", Keyboards.CALENDAR_LABEL, Payload.of(CAL_VIEW));
        assertThat(lastTextTo("t-14b")).isEqualTo("📅 Ближайшие 14 дней ничего не запланировано.");
        assertThat(sender.sentTo("t-14b").getLast().keyboard()).isEqualTo(Keyboards.calendarActions());

        assertThat(press("t-14b", Payload.of(EVT_NEW))).isNull();
        assertThat(sender.sentTo("t-14b").getLast().keyboard()).isEqualTo(Keyboards.chooseEventType());
        press("t-14b", Payload.of(ETYPE_MEETING));
        assertThat(sender.edits.getLast().text()).contains("Пример: " + EventInput.EXAMPLE);

        text("t-14b", "в четверг вечером собрание");
        assertThat(lastTextTo("t-14b")).isEqualTo("Не понял дату. Пример: " + EventInput.EXAMPLE);

        text("t-14b", inThreeDays("18:30 Родительское собрание"));
        assertThat(lastTextTo("t-14b")).startsWith("Создать событие?").contains("👥").contains("Родительское собрание");

        assertThat(press("t-14b", Payload.of(DRAFT_SEND))).isEqualTo("✓ Отправлено");
        assertThat(events.findAll()).singleElement()
                .satisfies(event -> assertThat(event.getType()).isEqualTo(EventType.MEETING));
        assertThat(lastTextTo("p-14b-1")).startsWith("📅 Новое событие");
        assertThat(press("t-14b", Payload.of(DRAFT_SEND))).isEqualTo("Уже отправлено.");
    }

    @Test
    void dutyPlanButtonIsIdempotent() {
        TestClass c = createClass("14c", 2);
        addStudents(c, "Аня", "Боря");

        text("t-14c", Keyboards.DUTIES_LABEL, Payload.of(DUTY_VIEW));
        assertThat(lastTextTo("t-14c")).isEqualTo("🧹 График дежурств пуст.");
        assertThat(sender.sentTo("t-14c").getLast().keyboard()).isEqualTo(Keyboards.dutyActions());

        assertThat(press("t-14c", Payload.of(DUTY_PLAN))).isEqualTo("✓ Добавлено дежурств: 10");
        assertThat(sender.edits.getLast().text()).contains("— Аня").contains("— Боря");
        assertThat(press("t-14c", Payload.of(DUTY_PLAN))).isEqualTo("График на 2 недели уже составлен");
        assertThat(duties.count()).isEqualTo(10);
    }

    @Test
    void parentSeesCalendarButCannotPlan() {
        TestClass c = createClass("14d", 1);
        addStudents(c, "Аня");
        press("t-14d", Payload.of(DUTY_PLAN));

        text("p-14d-1", "что у нас на неделе?");
        assertThat(lastTextTo("p-14d-1")).isEqualTo(UpdateDispatcher.PARENT_TEXT_REPLY);
        assertThat(sender.sentTo("p-14d-1").getLast().keyboard()).isEqualTo(Keyboards.parentMenu());

        text("p-14d-1", Keyboards.CALENDAR_LABEL, Payload.of(CAL_VIEW));
        assertThat(lastTextTo("p-14d-1")).startsWith("📅 Ближайшие 14 дней:").contains("дежурит Аня");

        assertThat(press("p-14d-1", Payload.of(DUTY_PLAN))).isEqualTo("Кнопка устарела.");
        assertThat(press("p-14d-1", Payload.of(EVT_NEW))).isEqualTo("Кнопка устарела.");
    }

    @Test
    void calendarButtonDoesNotBecomeCustomPollAnswer() {
        createClass("14e", 1);
        long poll = pollService.publish("t-14e", PollType.CUSTOM, "Что подарить?", List.of()).id();
        press("p-14e-1", Payload.of(POLL_TEXT, poll));

        text("p-14e-1", Keyboards.CALENDAR_LABEL, Payload.of(CAL_VIEW));
        assertThat(answers.count()).isZero();

        text("p-14e-1", "Книгу");
        assertThat(lastTextTo("p-14e-1")).isEqualTo("✓ Ответ записан.");
    }
}
