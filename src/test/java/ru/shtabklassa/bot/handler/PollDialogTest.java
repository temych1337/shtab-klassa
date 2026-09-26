package ru.shtabklassa.bot.handler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.adapter.Keyboard;
import ru.shtabklassa.bot.keyboard.Keyboards;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.model.Poll;
import ru.shtabklassa.model.PollType;
import ru.shtabklassa.repository.PollAnswerRepository;
import ru.shtabklassa.repository.PollRepository;
import ru.shtabklassa.service.AnnouncementService;
import ru.shtabklassa.service.PollService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.shtabklassa.bot.keyboard.Payload.Action.*;

class PollDialogTest extends BotDialogTest {

    @Autowired PollService pollService;
    @Autowired AnnouncementService announcementService;
    @Autowired PollRepository polls;
    @Autowired PollAnswerRepository answers;

    @Test
    void teacherCreatesChoicePollThroughButtons() {
        createClass("10a", 3);

        text("t-10a", Keyboards.NEW_POLL_LABEL, Payload.of(NEW_POLL));
        assertThat(sender.sentTo("t-10a").getLast().keyboard()).isEqualTo(Keyboards.choosePollType());

        assertThat(press("t-10a", Payload.of(PTYPE_CHOICE))).isNull();
        assertThat(sender.edits.getLast().text()).isEqualTo("Напишите вопрос одним сообщением.");

        text("t-10a", "Куда едем в мае?");
        assertThat(lastTextTo("t-10a")).contains("каждый с новой строки");

        text("t-10a", "Планетарий");
        assertThat(lastTextTo("t-10a")).contains("от 2 до 6").contains("Пришлите ещё раз");

        text("t-10a", "Планетарий\nЗоопарк\nМузей");
        assertThat(lastTextTo("t-10a")).contains("Куда едем в мае?").contains("• Зоопарк");
        assertThat(sender.sentTo("t-10a").getLast().keyboard()).isEqualTo(Keyboards.confirmDraft());

        assertThat(press("t-10a", Payload.of(DRAFT_SEND))).isEqualTo("✓ Отправлено");
        Poll poll = polls.findAll().getFirst();
        assertThat(poll.getType()).isEqualTo(PollType.CHOICE);
        assertThat(poll.getOptions()).containsExactly("Планетарий", "Зоопарк", "Музей");
        assertThat(sender.sentTo("p-10a-1").getLast().keyboard().rows()).hasSize(3);
    }

    @Test
    void consentPollSkipsOptionsStep() {
        createClass("10b", 2);

        text("t-10b", Keyboards.NEW_POLL_LABEL);
        press("t-10b", Payload.of(PTYPE_CONSENT));
        text("t-10b", "Можно фото на сайт школы?");

        assertThat(lastTextTo("t-10b")).contains("Ответ: «Да / Нет»");
        assertThat(press("t-10b", Payload.of(DRAFT_SEND))).isEqualTo("✓ Отправлено");
        assertThat(polls.findAll()).singleElement().extracting(Poll::getType).isEqualTo(PollType.CONSENT);
    }

    @Test
    void tooLongQuestionIsRejectedBeforeSend() {
        createClass("10c", 1);

        text("t-10c", Keyboards.NEW_POLL_LABEL);
        press("t-10c", Payload.of(PTYPE_CUSTOM));
        text("t-10c", "в".repeat(1001));

        assertThat(lastTextTo("t-10c")).startsWith("Слишком длинно").endsWith("Пришлите ещё раз.");
        text("t-10c", "Что подарить?");
        assertThat(lastTextTo("t-10c")).contains("Ответ: свободный текст");
    }

    @Test
    void parentVotesByButtons() {
        createClass("10d", 2);
        long consent = pollService.publish("t-10d", PollType.CONSENT, "Да?", List.of()).id();
        long choice = pollService.publish("t-10d", PollType.CHOICE, "Куда?", List.of("Музей", "Парк")).id();

        assertThat(press("p-10d-1", Payload.of(POLL_YES, consent))).isEqualTo("✓ Отмечено: да");
        assertThat(press("p-10d-1", Payload.of(POLL_YES, consent))).isEqualTo("Уже отмечено: да");
        assertThat(press("p-10d-1", Payload.of(POLL_NO, consent))).isEqualTo("✓ Ответ изменён: нет");
        assertThat(press("p-10d-1", Payload.option(choice, 1))).isEqualTo("✓ Отмечено: Парк");
        assertThat(press("p-10d-1", Payload.option(choice, 5))).isEqualTo("Кнопка устарела.");
        assertThat(press("p-10d-1", Payload.of(POLL_OPT, choice))).isEqualTo("Кнопка устарела.");
        assertThat(press("p-10d-1", Payload.of(POLL_YES, choice))).isEqualTo("Кнопка устарела.");
    }

    @Test
    void parentAnswersCustomPollWithText() {
        createClass("10e", 2);
        long id = pollService.publish("t-10e", PollType.CUSTOM, "Что подарить учителю?", List.of()).id();
        assertThat(sender.sentTo("p-10e-1").getLast().keyboard().rows().getFirst())
                .extracting(Keyboard.Button::label).containsExactly("✏ Ответить");

        text("p-10e-1", "книгу");
        assertThat(lastTextTo("p-10e-1")).isEqualTo(UpdateDispatcher.PARENT_TEXT_REPLY);

        assertThat(press("p-10e-1", Payload.of(POLL_TEXT, id))).isEqualTo("Жду ответ");
        assertThat(lastTextTo("p-10e-1")).contains("Что подарить учителю?");

        text("p-10e-1", "   ");
        assertThat(lastTextTo("p-10e-1")).isEqualTo("Пустой ответ. Пришлите ещё раз.");
        text("p-10e-1", "Сертификат в книжный");
        assertThat(lastTextTo("p-10e-1")).isEqualTo("✓ Ответ записан.");
        assertThat(answers.findAll()).singleElement().extracting(a -> a.getAnswer()).isEqualTo("Сертификат в книжный");

        text("p-10e-1", "а ещё цветы");
        assertThat(lastTextTo("p-10e-1")).isEqualTo(UpdateDispatcher.PARENT_TEXT_REPLY);
        press("p-10e-1", Payload.of(POLL_TEXT, id));
        text("p-10e-1", "Сертификат и цветы");
        assertThat(lastTextTo("p-10e-1")).isEqualTo("✓ Ответ изменён.");
        assertThat(answers.count()).isEqualTo(1);
    }

    @Test
    void answerButtonOnNonCustomPollIsStale() {
        createClass("10f", 1);
        long id = pollService.publish("t-10f", PollType.CONSENT, "Да?", List.of()).id();

        assertThat(press("p-10f-1", Payload.of(POLL_TEXT, id))).isEqualTo("Кнопка устарела.");
        text("p-10f-1", "да");
        assertThat(lastTextTo("p-10f-1")).isEqualTo(UpdateDispatcher.PARENT_TEXT_REPLY);
    }

    @Test
    void remindButtonWorksOnceAndOnlyForTeacher() {
        createClass("10g", 3);
        long announcement = announcementService.publish("t-10g", "Сменка", false).id();
        long poll = pollService.publish("t-10g", PollType.CONSENT, "Да?", List.of()).id();
        pollService.answer(poll, "p-10g-1", Poll.YES);

        assertThat(press("t-10g", Payload.of(REMIND_ANN, announcement))).isEqualTo("🔔 Напомнили: 3");
        assertThat(press("t-10g", Payload.of(REMIND_ANN, announcement))).startsWith("Уже напомнили только что");
        assertThat(press("t-10g", Payload.of(REMIND_POLL, poll))).isEqualTo("🔔 Напомнили: 2");
        assertThat(press("p-10g-1", Payload.of(REMIND_POLL, poll))).isEqualTo("Кнопка устарела.");

        announcementService.mark(announcement, "p-10g-1", null);
        announcementService.mark(announcement, "p-10g-2", null);
        announcementService.mark(announcement, "p-10g-3", null);
        clock.advance(java.time.Duration.ofMinutes(11));
        assertThat(press("t-10g", Payload.of(REMIND_ANN, announcement))).isEqualTo("Все уже ответили 🎉");
    }

    @Test
    void teacherMenuHasBothButtons() {
        createClass("10h", 1);

        text("t-10h", "привет");

        assertThat(sender.sentTo("t-10h").getLast().keyboard().rows().getFirst())
                .extracting(Keyboard.Button::label)
                .containsExactly(Keyboards.NEW_ANNOUNCEMENT_LABEL, Keyboards.NEW_POLL_LABEL);
    }
}
