package ru.shtabklassa.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.adapter.MessageDeliveryException;
import ru.shtabklassa.adapter.MessageRef;
import ru.shtabklassa.model.Decision;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.AnnouncementReadRepository;
import ru.shtabklassa.repository.AnnouncementRepository;
import ru.shtabklassa.support.FakeMessageSender;
import ru.shtabklassa.support.IntegrationTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class AnnouncementServiceTest extends IntegrationTest {

    @Autowired AnnouncementService service;
    @Autowired AnnouncementRepository announcements;
    @Autowired AnnouncementReadRepository reads;
    @Autowired ReminderService reminders;

    private MessageRef summaryRef(long announcementId, User teacher) {
        return new MessageRef(teacher.getExternalId(), announcements.findById(announcementId).orElseThrow().getSummaryMessageId());
    }

    private List<String> summaryEdits(MessageRef summary) {
        return sender.edits.stream().filter(edit -> edit.message().equals(summary)).map(FakeMessageSender.Edit::text).toList();
    }

    private void awaitSummary(MessageRef summary, String expectedFragment) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(summaryEdits(summary)).last().asString().contains(expectedFragment));
    }

    @Test
    void publishSendsTeacherSummaryBeforeBroadcast() {
        TestClass c = createClass("5a", 27);

        PublishResult result = service.publish(c.teacher().getExternalId(), "Собрание в четверг в 18:00", true);

        assertThat(result.delivered()).isEqualTo(27);
        assertThat(result.failed()).isZero();
        FakeMessageSender.Sent first = sender.sent.getFirst();
        assertThat(first.peerId()).isEqualTo(c.teacher().getExternalId());
        assertThat(first.text()).contains("Собрание в четверг").contains("Ответили 0 из 27 · Согласны: 0 · Не смогут: 0");
        assertThat(sender.sent).hasSize(28);
        FakeMessageSender.Sent toParent = sender.sentTo("p-5a-1").getFirst();
        assertThat(toParent.text()).contains("Анна Сергеевна").contains("Собрание в четверг");
        assertThat(toParent.keyboard().rows().getFirst()).extracting(b -> b.label()).containsExactly("Согласен", "Не смогу");
    }

    // был баг: publish падал уже после рассылки, учитель жал Отправить ещё раз -> всем дубль
    @Test
    void failedSummaryEditAfterBroadcastDoesNotFailPublish() {
        TestClass c = createClass("5k", 3);
        sender.failingPeers.add("p-5k-3");
        sender.failingEdits.add(c.teacher().getExternalId());

        PublishResult result = service.publish(c.teacher().getExternalId(), "Родительское собрание", true);

        assertThat(result.delivered()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(sender.sentTo("p-5k-1")).hasSize(1);
    }

    @Test
    void readOnlyAnnouncementCountsReads() {
        TestClass c = createClass("5b", 27);
        long id = service.publish(c.teacher().getExternalId(), "Завтра нужна сменка", false).id();
        MessageRef summary = summaryRef(id, c.teacher());

        assertThat(service.summarize(id).render()).contains("Прочитали 0 из 27");
        assertThat(service.mark(id, "p-5b-1", null)).isEqualTo(MarkOutcome.RECORDED);

        awaitSummary(summary, "Прочитали 1 из 27");
    }

    @Test
    void secondClickOnSameButtonChangesNothing() {
        TestClass c = createClass("5c", 3);
        long id = service.publish(c.teacher().getExternalId(), "Экскурсия", true).id();

        assertThat(service.mark(id, "p-5c-1", Decision.AGREE)).isEqualTo(MarkOutcome.RECORDED);
        assertThat(service.mark(id, "p-5c-1", Decision.AGREE)).isEqualTo(MarkOutcome.UNCHANGED);
        assertThat(service.mark(id, "p-5c-1", Decision.AGREE)).isEqualTo(MarkOutcome.UNCHANGED);

        assertThat(reads.count()).isEqualTo(1);
        assertThat(service.summarize(id).agreed()).isEqualTo(1);
    }

    @Test
    void readOnlyDoubleClickIsUnchanged() {
        TestClass c = createClass("5d", 3);
        long id = service.publish(c.teacher().getExternalId(), "Сменка", false).id();

        service.mark(id, "p-5d-1", null);

        assertThat(service.mark(id, "p-5d-1", null)).isEqualTo(MarkOutcome.UNCHANGED);
        assertThat(service.summarize(id).answered()).isEqualTo(1);
    }

    @Test
    void parentCanChangeDecision() {
        TestClass c = createClass("5e", 3);
        long id = service.publish(c.teacher().getExternalId(), "Экскурсия", true).id();
        MessageRef summary = summaryRef(id, c.teacher());

        service.mark(id, "p-5e-1", Decision.AGREE);
        assertThat(service.mark(id, "p-5e-1", Decision.DECLINE)).isEqualTo(MarkOutcome.CHANGED);

        AnnouncementSummary counts = service.summarize(id);
        assertThat(counts.answered()).isEqualTo(1);
        assertThat(counts.agreed()).isZero();
        assertThat(counts.declined()).isEqualTo(1);
        awaitSummary(summary, "Ответили 1 из 3 · Согласны: 0 · Не смогут: 1");
    }

    @Test
    void concurrentClicksFromWholeClassGiveExactCounts() throws Exception {
        TestClass c = createClass("5f", 27);
        long id = service.publish(c.teacher().getExternalId(), "Экскурсия в планетарий", true).id();
        MessageRef summary = summaryRef(id, c.teacher());

        // каждый родитель жмёт дважды одновременно: нечётные "Согласен", чётные "Не смогу"
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MarkOutcome>> clicks = new ArrayList<>();
        for (int i = 1; i <= 27; i++) {
            String parent = "p-5f-" + i;
            Decision decision = i % 2 == 1 ? Decision.AGREE : Decision.DECLINE;
            for (int click = 0; click < 2; click++) {
                clicks.add(pool.submit(() -> {
                    start.await();
                    return service.mark(id, parent, decision);
                }));
            }
        }
        start.countDown();
        List<MarkOutcome> outcomes = new ArrayList<>();
        for (Future<MarkOutcome> click : clicks) {
            outcomes.add(click.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).filteredOn(o -> o == MarkOutcome.RECORDED).hasSize(27);
        assertThat(outcomes).filteredOn(o -> o == MarkOutcome.UNCHANGED).hasSize(27);
        assertThat(reads.count()).isEqualTo(27);
        awaitSummary(summary, "✅ Ответили 27 из 27 · Согласны: 14 · Не смогут: 13");
        assertThat(summaryEdits(summary).size()).isLessThan(27);
    }

    @Test
    void summaryEditsAreCoalescedButLastOneIsExact() {
        TestClass c = createClass("5g", 27);
        long id = service.publish(c.teacher().getExternalId(), "Сменка", false).id();
        MessageRef summary = summaryRef(id, c.teacher());

        for (int i = 1; i <= 27; i++) {
            service.mark(id, "p-5g-" + i, null);
        }

        awaitSummary(summary, "✅ Прочитали 27 из 27");
        assertThat(summaryEdits(summary).size()).isLessThan(27);
    }

    @Test
    void partialDeliveryFailureIsShownToTeacher() {
        TestClass c = createClass("5h", 27);
        sender.failingPeers.addAll(List.of("p-5h-3", "p-5h-5"));
        User noAccount = users.save(new User(null, Role.PARENT, c.klass().getId(), "Без VK"));

        PublishResult result = service.publish(c.teacher().getExternalId(), "Сбор на подарок", true);

        assertThat(result.delivered()).isEqualTo(25);
        assertThat(result.failed()).isEqualTo(3);
        assertThat(noAccount.getId()).isNotNull();
        MessageRef summary = summaryRef(result.id(), c.teacher());
        assertThat(summaryEdits(summary)).last().asString()
                .contains("Ответили 0 из 28")
                .contains("Не доставлено: 3");
    }

    @Test
    void failedTeacherSummaryStopsBeforeBroadcast() {
        TestClass c = createClass("5i", 3);
        sender.failingPeers.add(c.teacher().getExternalId());

        assertThatThrownBy(() -> service.publish(c.teacher().getExternalId(), "текст", false))
                .isInstanceOf(MessageDeliveryException.class);
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void rejectsWrongPeopleAndWrongButtons() {
        TestClass a = createClass("6a", 2);
        TestClass b = createClass("6b", 2);
        long readOnly = service.publish(a.teacher().getExternalId(), "Сменка", false).id();
        long withDecision = service.publish(a.teacher().getExternalId(), "Экскурсия", true).id();

        assertThatThrownBy(() -> service.mark(readOnly, "p-6b-1", null)).isInstanceOf(DomainException.NotAllowed.class);
        assertThatThrownBy(() -> service.mark(readOnly, "t-6a", null)).isInstanceOf(DomainException.NotAllowed.class);
        assertThatThrownBy(() -> service.mark(readOnly, "stranger", null)).isInstanceOf(DomainException.UnknownUser.class);
        assertThatThrownBy(() -> service.mark(readOnly, "p-6a-1", Decision.AGREE)).isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> service.mark(withDecision, "p-6a-1", null)).isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> service.mark(999_999, "p-6a-1", null)).isInstanceOf(DomainException.NotFound.class);
        assertThat(reads.count()).isZero();
        assertThat(b.parents()).hasSize(2);
    }

    @Test
    void onlyTeacherPublishesAndTextMustBeReal() {
        TestClass c = createClass("6c", 2);

        assertThatThrownBy(() -> service.publish("p-6c-1", "текст", false)).isInstanceOf(DomainException.NotAllowed.class);
        assertThatThrownBy(() -> service.publish("t-6c", "   ", false)).isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> service.publish("t-6c", "x".repeat(AnnouncementService.MAX_TEXT_LENGTH + 1), false))
                .isInstanceOf(DomainException.InvalidInput.class);
        assertThat(announcements.count()).isZero();
        assertThat(c.parents()).hasSize(2);
    }

    // MAX режет всё длиннее 4000, а к тексту ещё приклеивается имя и Напоминание
    @Test
    void longestAllowedAnnouncementFitsMessengerLimitEvenAsReminder() {
        createClass("6e", 2);
        long id = service.publish("t-6e", "я".repeat(AnnouncementService.MAX_TEXT_LENGTH), false).id();

        reminders.remindSilent("t-6e", SummaryTarget.announcement(id));

        assertThat(sender.sentTo("p-6e-1")).hasSize(2).allSatisfy(message -> assertThat(message.text()).hasSizeLessThanOrEqualTo(4000));
    }

    @Test
    void emptyClassShowsZeroOfZeroWithoutCheckmark() {
        TestClass c = createClass("6d", 0);

        long id = service.publish(c.teacher().getExternalId(), "Никого нет", false).id();

        assertThat(service.summarize(id).render()).contains("Прочитали 0 из 0").doesNotContain("✅");
    }
}
