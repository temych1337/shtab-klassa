package ru.shtabklassa.bot.handler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.adapter.Keyboard;
import ru.shtabklassa.adapter.MessageRef;
import ru.shtabklassa.bot.keyboard.Keyboards;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.model.Decision;
import ru.shtabklassa.model.PollType;
import ru.shtabklassa.repository.AnnouncementReadRepository;
import ru.shtabklassa.repository.PollAnswerRepository;
import ru.shtabklassa.service.AnnouncementService;
import ru.shtabklassa.service.PollService;
import ru.shtabklassa.support.FakeMessageSender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.shtabklassa.bot.keyboard.Payload.Action.*;

class ComfortDialogTest extends BotDialogTest {

    @Autowired AnnouncementService announcementService;
    @Autowired PollService pollService;
    @Autowired AnnouncementReadRepository reads;
    @Autowired PollAnswerRepository answers;

    // press() жмёт под сообщением "555"
    private List<FakeMessageSender.Edit> cardEdits(String parent) {
        return sender.edits.stream().filter(edit -> edit.message().equals(new MessageRef(parent, "555"))).toList();
    }

    @Test
    void parentSeesOwnAnswerInTheMessageAndCanChangeIt() {
        createClass("17a", 2);
        long id = announcementService.publish("t-17a", "Экскурсия в пятницу", true).id();

        press("p-17a-1", Payload.of(ANN_AGREE, id));

        FakeMessageSender.Edit card = cardEdits("p-17a-1").getLast();
        assertThat(card.text()).contains("Экскурсия в пятницу").endsWith("✓ Ваш ответ: согласен");
        assertThat(card.keyboard()).as("кнопки остаются — можно передумать")
                .isEqualTo(Keyboards.forAnnouncement(id, true));

        press("p-17a-1", Payload.of(ANN_AGREE, id));
        assertThat(cardEdits("p-17a-1")).as("повторный клик ничего не перерисовывает").hasSize(1);

        press("p-17a-1", Payload.of(ANN_DECLINE, id));
        assertThat(cardEdits("p-17a-1").getLast().text()).endsWith("✓ Ваш ответ: не смогу");
    }

    @Test
    void readButtonDisappearsAfterReading() {
        createClass("17b", 1);
        long id = announcementService.publish("t-17b", "Сменка с понедельника", false).id();

        press("p-17b-1", Payload.of(ANN_READ, id));

        FakeMessageSender.Edit card = cardEdits("p-17b-1").getLast();
        assertThat(card.text()).endsWith("✓ Отмечено: прочитал");
        assertThat(card.keyboard()).isNull();
    }

    @Test
    void pollCardShowsChosenOption() {
        createClass("17c", 1);
        long id = pollService.publish("t-17c", PollType.CHOICE, "Куда едем?", List.of("Музей", "Парк")).id();

        press("p-17c-1", Payload.option(id, 1));

        assertThat(cardEdits("p-17c-1").getLast().text()).endsWith("✓ Ваш ответ: Парк");
    }

    @Test
    void customTextAnswerRedrawsTheDeliveredPoll() {
        createClass("17d", 1);
        long id = pollService.publish("t-17d", PollType.CUSTOM, "Что подарить?", List.of()).id();
        String delivered = sender.sentTo("p-17d-1").getLast().messageId();

        press("p-17d-1", Payload.of(POLL_TEXT, id));
        text("p-17d-1", "Книгу про космос");

        FakeMessageSender.Edit card = sender.edits.stream()
                .filter(edit -> edit.message().equals(new MessageRef("p-17d-1", delivered))).toList().getLast();
        assertThat(card.text()).endsWith("✓ Ваш ответ: «Книгу про космос»");
        assertThat(card.keyboard().rows().getFirst()).extracting(Keyboard.Button::label).containsExactly("✏ Ответить");
    }

    @Test
    void failedRedrawDoesNotLoseTheAnswer() {
        createClass("17e", 1);
        long id = announcementService.publish("t-17e", "Экскурсия", true).id();
        sender.failingEdits.add("p-17e-1");

        assertThat(press("p-17e-1", Payload.of(ANN_AGREE, id))).isEqualTo("✓ Отмечено: согласен");

        assertThat(reads.findAll()).singleElement()
                .satisfies(read -> assertThat(read.getDecision()).isEqualTo(Decision.AGREE));
    }

    @Test
    void teacherGetsNamesOfSilentParentsOnlyOnce() {
        TestClass c = createClass("17f", 3);
        addStudents(c, "Миша Петров", "Катя Иванова", "Лёва Сидоров");
        long id = announcementService.publish("t-17f", "Экскурсия в планетарий", true).id();
        announcementService.mark(id, "p-17f-1", Decision.AGREE);
        assertThat(sender.sentTo("t-17f").getFirst().keyboard().rows().get(1)).extracting(Keyboard.Button::label)
                .containsExactly("📥 Скачать отчёт", "👥 Кто не ответил");

        assertThat(press("t-17f", Payload.of(SILENT_ANN, id))).isEqualTo("👥 Не ответили: 2");

        String list = lastTextTo("t-17f");
        assertThat(list).startsWith("👥 Не ответили (2) — «Экскурсия в планетарий»:")
                .contains("Родитель 2", "Родитель 3")
                .doesNotContain("Родитель 1")
                .as("только имена родителей — без детей").doesNotContain("Катя", "Лёва");

        int messagesBefore = sender.sentTo("t-17f").size();
        assertThat(press("t-17f", Payload.of(SILENT_ANN, id))).isEqualTo("Список уже выше в чате");
        assertThat(sender.sentTo("t-17f")).hasSize(messagesBefore);

        announcementService.mark(id, "p-17f-2", Decision.DECLINE);
        assertThat(press("t-17f", Payload.of(SILENT_ANN, id))).as("состав изменился — новый список")
                .isEqualTo("👥 Не ответили: 1");
    }

    @Test
    void silentListRespectsRolesAndClasses() {
        createClass("17g", 1);
        createClass("17h", 1);
        long id = pollService.publish("t-17g", PollType.CONSENT, "Фото можно?", List.of()).id();

        assertThat(press("p-17g-1", Payload.of(SILENT_POLL, id))).isEqualTo("Кнопка устарела.");
        assertThat(press("t-17h", Payload.of(SILENT_POLL, id))).isEqualTo("Это не ваш класс.");

        pollService.answer(id, "p-17g-1", "YES");
        assertThat(press("t-17g", Payload.of(SILENT_POLL, id))).isEqualTo(ReminderHandler.ALL_ANSWERED);
    }

    @Test
    void startGreetsTeacherAndParentByName() {
        createClass("17i", 1);

        text("t-17i", "/start");
        assertThat(lastTextTo("t-17i")).startsWith("Здравствуйте, Анна! Это штаб класса 17i");
        assertThat(sender.sentTo("t-17i").getLast().keyboard()).isEqualTo(Keyboards.teacherMenu());

        text("p-17i-1", "Начать");
        assertThat(lastTextTo("p-17i-1")).startsWith("Здравствуйте, Родитель! Это бот класса 17i.")
                .contains("Классный руководитель — Анна Сергеевна.");
        assertThat(sender.sentTo("p-17i-1").getLast().keyboard()).isEqualTo(Keyboards.parentMenu());

        text("t-17i", "как дела");
        assertThat(lastTextTo("t-17i")).startsWith("Анна, что делаем?");
    }

    @Test
    void startDropsHalfFinishedDraftsAndAnswers() {
        createClass("17j", 1);
        long poll = pollService.publish("t-17j", PollType.CUSTOM, "Что подарить?", List.of()).id();

        text("t-17j", Keyboards.NEW_ANNOUNCEMENT_LABEL, Payload.of(NEW_ANN));
        text("t-17j", "/start");
        text("t-17j", "это не текст объявления");
        assertThat(lastTextTo("t-17j")).as("черновик сброшен — бот не ждёт текст").contains("что делаем?");

        press("p-17j-1", Payload.of(POLL_TEXT, poll));
        text("p-17j-1", "/start");
        text("p-17j-1", "просто вопрос");
        assertThat(answers.count()).isZero();
        assertThat(lastTextTo("p-17j-1")).isEqualTo(UpdateDispatcher.PARENT_TEXT_REPLY);
    }
}
