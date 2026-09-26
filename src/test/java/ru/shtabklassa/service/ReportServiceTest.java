package ru.shtabklassa.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.model.Decision;
import ru.shtabklassa.model.Poll;
import ru.shtabklassa.model.PollType;
import ru.shtabklassa.support.IntegrationTest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportServiceTest extends IntegrationTest {

    @Autowired ReportService reports;
    @Autowired AnnouncementService announcementService;
    @Autowired PollService pollService;

    // строки без BOM, по \r\n
    private static List<String> lines(byte[] csv) {
        assertThat(Arrays.copyOf(csv, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String body = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        return Arrays.asList(body.split("\r\n"));
    }

    @Test
    void announcementReportListsEveryParentSilentFirst() {
        TestClass c = createClass("15a", 4);
        addStudents(c, "Миша Петров", "Катя Иванова");
        sender.failingPeers.add("p-15a-3");
        long id = announcementService.publish("t-15a", "Экскурсия в пятницу", true).id();
        announcementService.mark(id, "p-15a-1", Decision.AGREE);
        announcementService.mark(id, "p-15a-2", Decision.DECLINE);

        ReportService.Report report = reports.build("t-15a", SummaryTarget.announcement(id));

        List<String> csv = lines(report.content());
        assertThat(csv.getFirst()).isEqualTo("Родитель;Дети;Статус;Ответ;Время ответа;Доставка");
        assertThat(csv).hasSize(5);
        assertThat(csv.get(1)).isEqualTo("Родитель 3;;Не ответил;;;Не доставлено");
        assertThat(csv.get(2)).isEqualTo("Родитель 4;;Не ответил;;;Доставлено");
        assertThat(csv.get(3)).startsWith("Родитель 1;Миша Петров;Ответил;Согласен;").endsWith(";Доставлено")
                .matches(".*;\\d\\d\\.\\d\\d\\.\\d{4} \\d\\d:\\d\\d;.*");
        assertThat(csv.get(4)).startsWith("Родитель 2;Катя Иванова;Ответил;Не смогу;");
        assertThat(report.fileName()).matches("report-announcement-" + id + "-\\d{4}-\\d\\d-\\d\\d\\.csv");
        assertThat(report.caption()).isEqualTo("📥 Отчёт: Экскурсия в пятницу · Ответили 2 из 4");
    }

    @Test
    void readOnlyAnnouncementSaysRead() {
        createClass("15b", 1);
        long id = announcementService.publish("t-15b", "Сменка", false).id();
        announcementService.mark(id, "p-15b-1", null);

        assertThat(lines(reports.build("t-15b", SummaryTarget.announcement(id)).content()).get(1))
                .startsWith("Родитель 1;;Ответил;Прочитал;");
    }

    @Test
    void choicePollShowsOptionNamesNotIndexes() {
        createClass("15c", 2);
        long id = pollService.publish("t-15c", PollType.CHOICE, "Куда едем?", List.of("Музей", "Парк")).id();
        pollService.answer(id, "p-15c-1", "1");

        List<String> csv = lines(reports.build("t-15c", SummaryTarget.poll(id)).content());

        assertThat(csv.get(1)).startsWith("Родитель 2;;Не ответил;");
        assertThat(csv.get(2)).startsWith("Родитель 1;;Ответил;Парк;");
    }

    @Test
    void consentPollSaysYesNo() {
        createClass("15d", 1);
        long id = pollService.publish("t-15d", PollType.CONSENT, "Фото можно?", List.of()).id();
        pollService.answer(id, "p-15d-1", Poll.NO);

        assertThat(lines(reports.build("t-15d", SummaryTarget.poll(id)).content()).get(1))
                .startsWith("Родитель 1;;Ответил;Нет;");
    }

    @Test
    void customAnswersAreFullAndSafeForExcel() {
        createClass("15e", 3);
        long id = pollService.publish("t-15e", PollType.CUSTOM, "Идеи подарка?", List.of()).id();
        String longIdea = "Сертификат в книжный, " + "очень ".repeat(20) + "хороший";
        pollService.answer(id, "p-15e-1", longIdea);
        pollService.answer(id, "p-15e-2", "=HYPERLINK(\"http://evil\";\"жми\")");
        pollService.answer(id, "p-15e-3", "цветы; торт");

        List<String> csv = lines(reports.build("t-15e", SummaryTarget.poll(id)).content());

        assertThat(csv.get(1)).as("полный текст, без обрезки как в сводке").contains(";" + longIdea + ";");
        assertThat(csv.get(2)).contains(";\"'=HYPERLINK(\"\"http://evil\"\";\"\"жми\"\")\";");
        assertThat(csv.get(3)).contains(";\"цветы; торт\";");
    }

    @Test
    void onlyTeacherOfThatClassGetsReport() {
        createClass("15f", 1);
        createClass("15g", 1);
        long id = announcementService.publish("t-15f", "Сменка", false).id();

        assertThatThrownBy(() -> reports.build("t-15g", SummaryTarget.announcement(id))).isInstanceOf(DomainException.NotAllowed.class);
        assertThatThrownBy(() -> reports.build("p-15f-1", SummaryTarget.announcement(id))).isInstanceOf(DomainException.NotAllowed.class);
        assertThatThrownBy(() -> reports.build("t-15f", SummaryTarget.poll(id))).isInstanceOf(DomainException.NotFound.class);
        assertThatThrownBy(() -> reports.build("stranger", SummaryTarget.announcement(id))).isInstanceOf(DomainException.UnknownUser.class);
    }

    @Test
    void sendDeliversFileToTeacher() {
        createClass("15h", 2);
        long id = announcementService.publish("t-15h", "Сменка", false).id();

        reports.send("t-15h", SummaryTarget.announcement(id));

        assertThat(sender.documents).singleElement().satisfies(document -> {
            assertThat(document.peerId()).isEqualTo("t-15h");
            assertThat(document.fileName()).endsWith(".csv");
            assertThat(lines(document.content())).hasSize(3);
        });
    }

    @Test
    void doubleClickWhileUploadingSendsOneFile() {
        createClass("15i", 2);
        long id = announcementService.publish("t-15i", "Сменка", false).id();
        sender.documentUploadMillis = 300;

        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<String>> clicks = List.of(
                CompletableFuture.supplyAsync(() -> click(start, id)),
                CompletableFuture.supplyAsync(() -> click(start, id)));
        start.countDown();

        assertThat(clicks.stream().map(CompletableFuture::join).toList()).containsExactlyInAnyOrder("sent", "busy");
        assertThat(sender.documents).hasSize(1);

        reports.send("t-15i", SummaryTarget.announcement(id));
        assertThat(sender.documents).as("после отправки можно снова").hasSize(2);
    }

    private String click(CountDownLatch start, long id) {
        try {
            start.await();
            reports.send("t-15i", SummaryTarget.announcement(id));
            return "sent";
        } catch (DomainException.TooSoon e) {
            return "busy";
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void failedUploadDoesNotLockTheButton() {
        createClass("15j", 1);
        long id = announcementService.publish("t-15j", "Сменка", false).id();
        sender.failingPeers.add("t-15j");

        assertThatThrownBy(() -> reports.send("t-15j", SummaryTarget.announcement(id)))
                .isInstanceOf(ru.shtabklassa.adapter.MessageDeliveryException.class);

        sender.failingPeers.clear();
        reports.send("t-15j", SummaryTarget.announcement(id));
        assertThat(sender.documents).hasSize(1);
    }
}
