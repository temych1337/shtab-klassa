package ru.shtabklassa.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.adapter.Keyboard;
import ru.shtabklassa.adapter.MessageRef;
import ru.shtabklassa.model.PollType;
import ru.shtabklassa.repository.AnnouncementRepository;
import ru.shtabklassa.support.FakeMessageSender;
import ru.shtabklassa.support.IntegrationTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ReminderServiceTest extends IntegrationTest {

    @Autowired ReminderService reminders;
    @Autowired AnnouncementService announcementService;
    @Autowired PollService pollService;
    @Autowired AnnouncementRepository announcements;

    private List<String> remindedPeers() {
        return sender.sent.stream()
                .filter(message -> message.text().startsWith("🔔 Напоминание"))
                .map(FakeMessageSender.Sent::peerId)
                .toList();
    }

    private long announcementWithTwoOfFiveRead(TestClass c) {
        long id = announcementService.publish(c.teacher().getExternalId(), "Завтра сменка", false).id();
        announcementService.mark(id, c.parents().get(0).getExternalId(), null);
        announcementService.mark(id, c.parents().get(1).getExternalId(), null);
        return id;
    }

    @Test
    void remindsOnlyThoseWhoHaveNotAnswered() {
        TestClass c = createClass("9a", 5);
        long id = announcementWithTwoOfFiveRead(c);

        ReminderService.ReminderResult result = reminders.remindSilent("t-9a", SummaryTarget.announcement(id));

        assertThat(result).isEqualTo(new ReminderService.ReminderResult(3, 0, 0));
        assertThat(remindedPeers()).containsExactlyInAnyOrder("p-9a-3", "p-9a-4", "p-9a-5");
        FakeMessageSender.Sent reminder = sender.sentTo("p-9a-3").getLast();
        assertThat(reminder.text()).contains("Завтра сменка");
        assertThat(reminder.keyboard().rows().getFirst()).extracting(Keyboard.Button::label).containsExactly("👀 Прочитал");
    }

    @Test
    void secondClickWithinCooldownSendsNothing() {
        TestClass c = createClass("9b", 5);
        long id = announcementWithTwoOfFiveRead(c);
        reminders.remindSilent("t-9b", SummaryTarget.announcement(id));

        assertThatThrownBy(() -> reminders.remindSilent("t-9b", SummaryTarget.announcement(id)))
                .isInstanceOf(DomainException.TooSoon.class)
                .hasMessage("Уже напомнили только что. Повторно — через 10 мин.");
        clock.advance(Duration.ofMinutes(4));
        assertThatThrownBy(() -> reminders.remindSilent("t-9b", SummaryTarget.announcement(id)))
                .hasMessage("Уже напомнили 4 мин назад. Повторно — через 6 мин.");
        assertThat(remindedPeers()).hasSize(3);
    }

    @Test
    void simultaneousClicksSendOneReminder() {
        TestClass c = createClass("9c", 5);
        long id = announcementWithTwoOfFiveRead(c);

        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<String>> clicks = List.of(
                CompletableFuture.supplyAsync(() -> clickRemind(start, id)),
                CompletableFuture.supplyAsync(() -> clickRemind(start, id)),
                CompletableFuture.supplyAsync(() -> clickRemind(start, id)));
        start.countDown();

        assertThat(clicks.stream().map(CompletableFuture::join).toList())
                .containsExactlyInAnyOrder("sent", "too soon", "too soon");
        assertThat(remindedPeers()).hasSize(3);
    }

    private String clickRemind(CountDownLatch start, long id) {
        try {
            start.await();
            reminders.remindSilent("t-9c", SummaryTarget.announcement(id));
            return "sent";
        } catch (DomainException.TooSoon e) {
            return "too soon";
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void afterCooldownRemindsThoseStillSilent() {
        TestClass c = createClass("9d", 5);
        long id = announcementWithTwoOfFiveRead(c);
        reminders.remindSilent("t-9d", SummaryTarget.announcement(id));
        announcementService.mark(id, "p-9d-3", null);
        sender.sent.clear();

        clock.advance(Duration.ofMinutes(10));
        ReminderService.ReminderResult second = reminders.remindSilent("t-9d", SummaryTarget.announcement(id));

        assertThat(second.reminded()).isEqualTo(2);
        assertThat(remindedPeers()).containsExactlyInAnyOrder("p-9d-4", "p-9d-5");
    }

    @Test
    void nobodySilentMeansNoMessagesAndNoCooldown() {
        TestClass c = createClass("9e", 2);
        long id = announcementService.publish("t-9e", "Сменка", true).id();
        announcementService.mark(id, "p-9e-1", ru.shtabklassa.model.Decision.AGREE);
        announcementService.mark(id, "p-9e-2", ru.shtabklassa.model.Decision.DECLINE);

        assertThat(reminders.remindSilent("t-9e", SummaryTarget.announcement(id)).nobodySilent()).isTrue();
        assertThat(remindedPeers()).isEmpty();
        assertThat(announcements.findById(id).orElseThrow().getLastRemindedAt()).isNull();
        assertThat(c.parents()).hasSize(2);
    }

    @Test
    void reminderThatReachedNobodyDoesNotBlockRetry() {
        TestClass c = createClass("9f", 3);
        long id = announcementService.publish("t-9f", "Сменка", false).id();
        sender.failingPeers.addAll(List.of("p-9f-1", "p-9f-2", "p-9f-3"));

        assertThat(reminders.remindSilent("t-9f", SummaryTarget.announcement(id)))
                .isEqualTo(new ReminderService.ReminderResult(0, 3, 0));

        sender.failingPeers.clear();
        assertThat(reminders.remindSilent("t-9f", SummaryTarget.announcement(id)).reminded()).isEqualTo(3);
        assertThat(c.parents()).hasSize(3);
    }

    @Test
    void successfulReminderClearsEarlierDeliveryFailure() {
        TestClass c = createClass("9g", 3);
        sender.failingPeers.add("p-9g-2");
        long id = announcementService.publish("t-9g", "Сменка", false).id();
        MessageRef summary = new MessageRef("t-9g", announcements.findById(id).orElseThrow().getSummaryMessageId());
        assertThat(announcementService.summarize(id).undelivered()).isEqualTo(1);

        sender.failingPeers.clear();
        reminders.remindSilent("t-9g", SummaryTarget.announcement(id));

        assertThat(announcementService.summarize(id).undelivered()).isZero();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(sender.edits.stream()
                .filter(edit -> edit.message().equals(summary)).toList().getLast().text())
                .doesNotContain("Не доставлено"));
        assertThat(c.parents()).hasSize(3);
    }

    @Test
    void pollReminderResendsPollButtons() {
        TestClass c = createClass("9h", 3);
        long id = pollService.publish("t-9h", PollType.CHOICE, "Куда едем?", List.of("Музей", "Парк")).id();
        pollService.answer(id, "p-9h-1", "0");

        assertThat(reminders.remindSilent("t-9h", SummaryTarget.poll(id)).reminded()).isEqualTo(2);

        FakeMessageSender.Sent reminder = sender.sentTo("p-9h-2").getLast();
        assertThat(reminder.text()).startsWith("🔔 Напоминание").contains("Куда едем?");
        assertThat(reminder.keyboard().rows()).extracting(row -> row.getFirst().label()).containsExactly("Музей", "Парк");
        assertThat(sender.sentTo("p-9h-1")).hasSize(1);
        assertThat(c.parents()).hasSize(3);
    }

    @Test
    void summaryButtonShowsSilentCountAndDisappearsWhenAllAnswered() {
        TestClass c = createClass("9i", 2);
        long id = announcementService.publish("t-9i", "Сменка", false).id();
        MessageRef summary = new MessageRef("t-9i", announcements.findById(id).orElseThrow().getSummaryMessageId());
        assertThat(sender.sentTo("t-9i").getFirst().keyboard().rows().getFirst().getFirst().label())
                .isEqualTo("🔔 Напомнить молчащим (2)");

        announcementService.mark(id, "p-9i-1", null);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(lastSummaryKeyboard(summary))
                .isNotNull()
                .satisfies(keyboard -> assertThat(keyboard.rows().getFirst().getFirst().label()).endsWith("(1)")));

        announcementService.mark(id, "p-9i-2", null);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(sender.edits.stream()
                .filter(edit -> edit.message().equals(summary)).toList().getLast())
                .satisfies(edit -> {
                    assertThat(edit.text()).contains("✅ Прочитали 2 из 2");
                    assertThat(edit.keyboard().rows()).singleElement()
                            .satisfies(row -> assertThat(row.getFirst().label()).isEqualTo("📥 Скачать отчёт"));
                }));
        assertThat(c.parents()).hasSize(2);
    }

    private Keyboard lastSummaryKeyboard(MessageRef summary) {
        List<FakeMessageSender.Edit> edits = sender.edits.stream().filter(edit -> edit.message().equals(summary)).toList();
        return edits.isEmpty() ? null : edits.getLast().keyboard();
    }

    @Test
    void onlyTeacherOfThatClassCanRemind() {
        TestClass a = createClass("9j", 2);
        createClass("9k", 1);
        long id = announcementService.publish("t-9j", "Сменка", false).id();

        assertThatThrownBy(() -> reminders.remindSilent("t-9k", SummaryTarget.announcement(id)))
                .isInstanceOf(DomainException.NotAllowed.class);
        assertThatThrownBy(() -> reminders.remindSilent("p-9j-1", SummaryTarget.announcement(id)))
                .isInstanceOf(DomainException.NotAllowed.class);
        assertThatThrownBy(() -> reminders.remindSilent("t-9j", SummaryTarget.poll(id)))
                .isInstanceOf(DomainException.NotFound.class);
        assertThat(remindedPeers()).isEmpty();
        assertThat(a.parents()).hasSize(2);
    }
}
