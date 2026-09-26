package ru.shtabklassa.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.adapter.Keyboard;
import ru.shtabklassa.adapter.MessageRef;
import ru.shtabklassa.model.Poll;
import ru.shtabklassa.model.PollType;
import ru.shtabklassa.repository.PollAnswerRepository;
import ru.shtabklassa.repository.PollRepository;
import ru.shtabklassa.support.FakeMessageSender;
import ru.shtabklassa.support.IntegrationTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class PollServiceTest extends IntegrationTest {

    @Autowired PollService service;
    @Autowired PollRepository polls;
    @Autowired PollAnswerRepository answers;

    private static final List<String> TRIP = List.of("Планетарий", "Зоопарк", "Музей");

    private MessageRef summaryRef(long pollId, TestClass c) {
        return new MessageRef(c.teacher().getExternalId(), polls.findById(pollId).orElseThrow().getSummaryMessageId());
    }

    private void awaitSummary(MessageRef summary, String expectedFragment) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(sender.edits.stream()
                .filter(edit -> edit.message().equals(summary))
                .map(FakeMessageSender.Edit::text)
                .toList()).last().asString().contains(expectedFragment));
    }

    @Test
    void consentPollCountsYesAndNo() {
        TestClass c = createClass("8a", 27);

        long id = service.publish(c.teacher().getExternalId(), PollType.CONSENT, "Можно фотографировать детей?", List.of()).id();

        FakeMessageSender.Sent teacherSummary = sender.sentTo(c.teacher().getExternalId()).getFirst();
        assertThat(teacherSummary.text()).contains("Ответили 0 из 27 · Да: 0 · Нет: 0");
        assertThat(teacherSummary.keyboard().rows().getFirst().getFirst().label()).isEqualTo("🔔 Напомнить молчащим (27)");
        assertThat(sender.sentTo("p-8a-1").getFirst().keyboard().rows().getFirst())
                .extracting(Keyboard.Button::label).containsExactly("Да", "Нет");

        assertThat(service.answer(id, "p-8a-1", Poll.YES)).isEqualTo(new PollService.AnswerResult(MarkOutcome.RECORDED, "да"));
        service.answer(id, "p-8a-2", Poll.NO);

        awaitSummary(summaryRef(id, c), "Ответили 2 из 27 · Да: 1 · Нет: 1");
    }

    @Test
    void failedSummaryEditAfterBroadcastDoesNotFailPublish() {
        TestClass c = createClass("8k", 3);
        sender.failingPeers.add("p-8k-3");
        sender.failingEdits.add(c.teacher().getExternalId());

        PublishResult result = service.publish(c.teacher().getExternalId(), PollType.CONSENT, "Едем?", List.of());

        assertThat(result.delivered()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void choicePollIsIdempotentAndAllowsChangingMind() {
        TestClass c = createClass("8b", 5);
        long id = service.publish(c.teacher().getExternalId(), PollType.CHOICE, "Куда едем?", TRIP).id();
        assertThat(sender.sentTo("p-8b-1").getFirst().keyboard().rows())
                .extracting(row -> row.getFirst().label()).containsExactlyElementsOf(TRIP);

        assertThat(service.answer(id, "p-8b-1", "1")).isEqualTo(new PollService.AnswerResult(MarkOutcome.RECORDED, "Зоопарк"));
        assertThat(service.answer(id, "p-8b-1", "1").outcome()).isEqualTo(MarkOutcome.UNCHANGED);
        assertThat(service.answer(id, "p-8b-1", "2")).isEqualTo(new PollService.AnswerResult(MarkOutcome.CHANGED, "Музей"));
        service.answer(id, "p-8b-2", "2");

        assertThat(answers.count()).isEqualTo(2);
        awaitSummary(summaryRef(id, c), "Ответили 2 из 5\n• Планетарий — 0\n• Зоопарк — 0\n• Музей — 2");
    }

    @Test
    void customPollShowsLatestAnswersWithNames() {
        TestClass c = createClass("8c", 5);
        long id = service.publish(c.teacher().getExternalId(), PollType.CUSTOM, "Что подарить учителю?", List.of()).id();

        for (int i = 1; i <= 4; i++) {
            service.answer(id, "p-8c-" + i, "идея " + i);
            clock.advance(Duration.ofSeconds(1));
        }

        PollSummary summary = service.summarize(id);
        assertThat(summary.answered()).isEqualTo(4);
        assertThat(summary.recentAnswers()).containsExactly(
                "«идея 4» — Родитель 4", "«идея 3» — Родитель 3", "«идея 2» — Родитель 2");
        assertThat(service.answer(id, "p-8c-1", "  идея 1  ").outcome()).isEqualTo(MarkOutcome.UNCHANGED);
    }

    @Test
    void wrongAnswersAreRejected() {
        TestClass a = createClass("8d", 2);
        createClass("8e", 1);
        long choice = service.publish(a.teacher().getExternalId(), PollType.CHOICE, "Куда?", TRIP).id();
        long consent = service.publish(a.teacher().getExternalId(), PollType.CONSENT, "Да?", List.of()).id();
        long custom = service.publish(a.teacher().getExternalId(), PollType.CUSTOM, "Что?", List.of()).id();

        assertThatThrownBy(() -> service.answer(choice, "p-8d-1", "3")).isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> service.answer(choice, "p-8d-1", "-1")).isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> service.answer(choice, "p-8d-1", "YES")).isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> service.answer(consent, "p-8d-1", "0")).isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> service.answer(custom, "p-8d-1", "   ")).isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> service.answer(custom, "p-8d-1", "x".repeat(1001))).isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> service.answer(consent, "p-8e-1", Poll.YES)).isInstanceOf(DomainException.NotAllowed.class);
        assertThatThrownBy(() -> service.answer(consent, "t-8d", Poll.YES)).isInstanceOf(DomainException.NotAllowed.class);
        assertThatThrownBy(() -> service.answer(424242, "p-8d-1", Poll.YES)).isInstanceOf(DomainException.NotFound.class);
        assertThat(answers.count()).isZero();
    }

    @Test
    void closedPollAcceptsNothing() {
        TestClass c = createClass("8f", 2);
        long id = service.publish(c.teacher().getExternalId(), PollType.CONSENT, "Да?", List.of()).id();
        Poll poll = polls.findById(id).orElseThrow();
        poll.close(clock.instant());
        polls.save(poll);

        assertThatThrownBy(() -> service.answer(id, "p-8f-1", Poll.YES))
                .isInstanceOf(DomainException.InvalidInput.class)
                .hasMessage("Опрос закрыт.");
    }

    @Test
    void concurrentVotesGiveExactCounts() throws Exception {
        TestClass c = createClass("8g", 27);
        long id = service.publish(c.teacher().getExternalId(), PollType.CHOICE, "Куда едем?", TRIP).id();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PollService.AnswerResult>> votes = new ArrayList<>();
        for (int i = 1; i <= 27; i++) {
            String parent = "p-8g-" + i;
            String option = Integer.toString(i % 3);
            for (int click = 0; click < 2; click++) {
                votes.add(pool.submit(() -> {
                    start.await();
                    return service.answer(id, parent, option);
                }));
            }
        }
        start.countDown();
        for (Future<PollService.AnswerResult> vote : votes) {
            vote.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(answers.count()).isEqualTo(27);
        assertThat(service.summarize(id).counts()).extracting(PollSummary.Count::count).containsExactly(9L, 9L, 9L);
        awaitSummary(summaryRef(id, c), "✅ Ответили 27 из 27");
    }

    @Test
    void optionsAreParsedFromLines() {
        assertThat(PollService.parseOptions("Планетарий\n\n  Зоопарк  \r\nМузей\n")).containsExactlyElementsOf(TRIP);
    }

    @Test
    void badOptionListsAreRejectedWithReadableReason() {
        assertThatThrownBy(() -> PollService.parseOptions("один")).hasMessageContaining("от 2 до 6");
        assertThatThrownBy(() -> PollService.parseOptions("1\n2\n3\n4\n5\n6\n7")).hasMessageContaining("от 2 до 6");
        assertThatThrownBy(() -> PollService.parseOptions(null)).isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> PollService.parseOptions("коротко\n" + "д".repeat(41))).hasMessageContaining("на кнопку не влезет");
        assertThatThrownBy(() -> PollService.parseOptions("Зоопарк\nзоопарк")).hasMessageContaining("повторяется");
    }

    @Test
    void publishValidatesAuthorAndContent() {
        TestClass c = createClass("8h", 1);

        assertThatThrownBy(() -> service.publish("p-8h-1", PollType.CONSENT, "Да?", List.of()))
                .isInstanceOf(DomainException.NotAllowed.class);
        assertThatThrownBy(() -> service.publish("t-8h", PollType.CHOICE, "Куда?", List.of("один")))
                .isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> service.publish("t-8h", PollType.CONSENT, " ", List.of()))
                .isInstanceOf(DomainException.InvalidInput.class);
        assertThat(polls.count()).isZero();
        assertThat(c.parents()).hasSize(1);
    }
}
