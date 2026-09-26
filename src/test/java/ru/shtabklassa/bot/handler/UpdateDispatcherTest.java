package ru.shtabklassa.bot.handler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.bot.filter.KnownUserFilter;
import ru.shtabklassa.bot.keyboard.Keyboards;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.repository.AnnouncementRepository;
import ru.shtabklassa.service.AnnouncementService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static ru.shtabklassa.bot.keyboard.Payload.Action.*;

class UpdateDispatcherTest extends BotDialogTest {

    @Autowired AnnouncementService announcementService;
    @Autowired AnnouncementRepository announcements;

    private void walkTeacherToConfirm(String teacher, String announcementText) {
        text(teacher, Keyboards.NEW_ANNOUNCEMENT_LABEL, Payload.of(NEW_ANN));
        text(teacher, announcementText, null);
        press(teacher, Payload.of(MODE_DECISION).toJson());
    }

    @Test
    void teacherCreatesAnnouncementThroughButtons() {
        TestClass c = createClass("7a", 3);
        String teacher = c.teacher().getExternalId();

        text(teacher, "привет", null);
        assertThat(sender.sentTo(teacher).getLast().keyboard()).isEqualTo(Keyboards.teacherMenu());

        text(teacher, Keyboards.NEW_ANNOUNCEMENT_LABEL, Payload.of(NEW_ANN));
        assertThat(sender.sentTo(teacher).getLast().text()).contains("Напишите текст");

        text(teacher, "Субботник в субботу", null);
        assertThat(sender.sentTo(teacher).getLast().keyboard()).isEqualTo(Keyboards.chooseMode());

        assertThat(press(teacher, Payload.of(MODE_DECISION).toJson())).isNull();
        assertThat(sender.edits.getLast().text()).contains("Субботник в субботу").contains("Согласен / Не смогу");

        assertThat(press(teacher, Payload.of(DRAFT_SEND).toJson())).isEqualTo("✓ Отправлено");
        assertThat(sender.edits.getLast().text()).isEqualTo("Отправлено родителям: 3");
        assertThat(announcements.findAll()).singleElement()
                .satisfies(a -> assertThat(a.isRequiresDecision()).isTrue());
    }

    // эмулятор зовёт диспетчер прямо из http, раньше тут был 500
    @Test
    void transportFailureOnTextReplyDoesNotEscapeDispatcher() {
        TestClass c = createClass("7f", 1);
        sender.failingPeers.add(c.teacher().getExternalId());

        assertThatCode(() -> text(c.teacher().getExternalId(), "привет", null)).doesNotThrowAnyException();
    }

    @Test
    void teacherTypingMenuLabelWithoutPayloadStillStartsDraft() {
        TestClass c = createClass("7b", 1);

        text(c.teacher().getExternalId(), Keyboards.NEW_ANNOUNCEMENT_LABEL, null);

        assertThat(sender.sentTo(c.teacher().getExternalId()).getLast().text()).contains("Напишите текст");
    }

    @Test
    void doubleClickOnSendPublishesOnce() throws Exception {
        TestClass c = createClass("7c", 5);
        String teacher = c.teacher().getExternalId();
        walkTeacherToConfirm(teacher, "Собрание");

        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<String>> clicks = List.of(
                CompletableFuture.supplyAsync(() -> awaitThenPress(start, teacher)),
                CompletableFuture.supplyAsync(() -> awaitThenPress(start, teacher)));
        start.countDown();

        List<String> replies = clicks.stream().map(CompletableFuture::join).toList();
        assertThat(replies).containsExactlyInAnyOrder("✓ Отправлено", "Уже отправлено.");
        assertThat(announcements.count()).isEqualTo(1);
    }

    private String awaitThenPress(CountDownLatch start, String teacher) {
        try {
            start.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return press(teacher, Payload.of(DRAFT_SEND).toJson());
    }

    @Test
    void failedSendKeepsDraftSoTeacherCanRetry() {
        TestClass c = createClass("7d", 2);
        String teacher = c.teacher().getExternalId();
        walkTeacherToConfirm(teacher, "Собрание");
        sender.failingPeers.add(teacher);

        assertThat(press(teacher, Payload.of(DRAFT_SEND).toJson())).isEqualTo(UpdateDispatcher.GENERIC_FAILURE);

        sender.failingPeers.clear();
        assertThat(press(teacher, Payload.of(DRAFT_SEND).toJson())).isEqualTo("✓ Отправлено");
    }

    @Test
    void cancelDropsDraft() {
        TestClass c = createClass("7e", 2);
        String teacher = c.teacher().getExternalId();
        walkTeacherToConfirm(teacher, "Собрание");

        press(teacher, Payload.of(DRAFT_CANCEL).toJson());

        assertThat(press(teacher, Payload.of(DRAFT_SEND).toJson())).isEqualTo("Уже отправлено.");
        assertThat(announcements.count()).isZero();
    }

    @Test
    void parentButtonsAnswerInstantlyAndIdempotently() {
        TestClass c = createClass("7f", 3);
        long id = announcementService.publish(c.teacher().getExternalId(), "Экскурсия", true).id();

        assertThat(press("p-7f-1", Payload.of(ANN_AGREE, id).toJson())).isEqualTo("✓ Отмечено: согласен");
        assertThat(press("p-7f-1", Payload.of(ANN_AGREE, id).toJson())).isEqualTo("Уже отмечено: согласен");
        assertThat(press("p-7f-1", Payload.of(ANN_DECLINE, id).toJson())).isEqualTo("✓ Ответ изменён: не смогу");
        assertThat(press("p-7f-1", Payload.of(ANN_READ, id).toJson())).isEqualTo("Кнопка устарела.");
    }

    @Test
    void everyCallbackGetsAnAnswerEvenWhenSomethingIsWrong() {
        TestClass c = createClass("7g", 1);

        assertThat(press("stranger", Payload.of(ANN_READ, 1).toJson())).isEqualTo(KnownUserFilter.UNKNOWN_USER_REPLY);
        assertThat(press("p-7g-1", "{not json")).isEqualTo("Кнопка устарела.");
        assertThat(press("p-7g-1", (String) null)).isEqualTo("Кнопка устарела.");
        assertThat(press("p-7g-1", Payload.of(ANN_READ, 424242).toJson())).isEqualTo("Объявление не найдено.");
        assertThat(press("p-7g-1", Payload.of(DRAFT_SEND).toJson())).isEqualTo("Кнопка устарела.");
        assertThat(c.parents()).hasSize(1);
    }

    @Test
    void textFromParentAndStrangerGetsShortReply() {
        createClass("7h", 1);

        text("p-7h-1", "а когда собрание?", null);
        text("stranger", "привет", null);

        assertThat(sender.sentTo("p-7h-1").getLast().text()).isEqualTo(UpdateDispatcher.PARENT_TEXT_REPLY);
        assertThat(sender.sentTo("stranger").getLast().text()).isEqualTo(KnownUserFilter.UNKNOWN_USER_REPLY);
    }
}
