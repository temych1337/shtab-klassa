package ru.shtabklassa.bot.handler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.adapter.Keyboard;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.model.PollType;
import ru.shtabklassa.service.AnnouncementService;
import ru.shtabklassa.service.PollService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.shtabklassa.bot.keyboard.Payload.Action.*;

class ReportDialogTest extends BotDialogTest {

    @Autowired AnnouncementService announcementService;
    @Autowired PollService pollService;

    @Test
    void summaryHasRemindAndReportButtons() {
        createClass("16a", 3);

        long id = pollService.publish("t-16a", PollType.CONSENT, "Фото можно?", List.of()).id();

        List<List<Keyboard.Button>> rows = sender.sentTo("t-16a").getFirst().keyboard().rows();
        assertThat(rows).extracting(row -> row.getFirst().label())
                .containsExactly("🔔 Напомнить молчащим (3)", "📥 Скачать отчёт");
        assertThat(rows.get(1).getFirst().payload()).isEqualTo(Payload.of(REPORT_POLL, id).toJson());
    }

    @Test
    void teacherPressesReportAndGetsFile() {
        createClass("16b", 2);
        long id = announcementService.publish("t-16b", "Сменка", false).id();

        assertThat(press("t-16b", Payload.of(REPORT_ANN, id))).isEqualTo(ReportHandler.SENT);

        assertThat(sender.documents).singleElement().satisfies(document -> {
            assertThat(document.peerId()).isEqualTo("t-16b");
            assertThat(document.caption()).startsWith("📥 Отчёт: Сменка");
        });
    }

    @Test
    void secondPressWhileUploadingIsRejectedPolitely() {
        createClass("16c", 2);
        long id = announcementService.publish("t-16c", "Сменка", false).id();
        sender.documentUploadMillis = 300;

        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<String>> presses = List.of(
                CompletableFuture.supplyAsync(() -> pressAfter(start, id)),
                CompletableFuture.supplyAsync(() -> pressAfter(start, id)));
        start.countDown();

        assertThat(presses.stream().map(CompletableFuture::join).toList())
                .containsExactlyInAnyOrder(ReportHandler.SENT, "Отчёт уже готовится.");
        assertThat(sender.documents).hasSize(1);
    }

    private String pressAfter(CountDownLatch start, long id) {
        try {
            start.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return press("t-16c", Payload.of(REPORT_ANN, id));
    }

    @Test
    void uploadFailureGetsReadableAnswer() {
        createClass("16d", 1);
        long id = announcementService.publish("t-16d", "Сменка", false).id();
        sender.failingPeers.add("t-16d");

        assertThat(press("t-16d", Payload.of(REPORT_ANN, id))).isEqualTo(ReportHandler.FAILED);
    }

    @Test
    void parentCannotDownloadReport() {
        createClass("16e", 1);
        long id = announcementService.publish("t-16e", "Сменка", false).id();

        assertThat(press("p-16e-1", Payload.of(REPORT_ANN, id))).isEqualTo("Кнопка устарела.");
        assertThat(press("p-16e-1", Payload.of(REPORT_POLL, id))).isEqualTo("Кнопка устарела.");
        assertThat(sender.documents).isEmpty();
    }
}
