package ru.shtabklassa.seed;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import ru.shtabklassa.model.ClassEntity;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.*;
import ru.shtabklassa.service.*;
import ru.shtabklassa.support.FakeMessageSender;
import ru.shtabklassa.support.IntegrationTest;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// сид отрабатывает сам при старте контекста (профиль seed)
@SpringBootTest(properties = {
        "seed.parent-ids=vk-111,vk-222",
        "spring.datasource.url=jdbc:h2:mem:seed-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles({"test", "seed"})
@Import(IntegrationTest.FakeTransport.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedRunnerTest {

    @Autowired SeedRunner seedRunner;
    @Autowired ClassRepository classes;
    @Autowired UserRepository users;
    @Autowired AnnouncementRepository announcements;
    @Autowired PollRepository polls;
    @Autowired DutyScheduleRepository duties;
    @Autowired CalendarEventRepository events;
    @Autowired AnnouncementService announcementService;
    @Autowired PollService pollService;
    @Autowired ReminderService reminders;
    @Autowired ReportService reports;
    @Autowired FakeMessageSender sender;

    private ClassEntity demoClass() {
        return classes.findByName(DemoFamilies.CLASS_NAME).orElseThrow();
    }

    private long excursionId() {
        return announcements.findAll().stream()
                .filter(a -> a.getClassId().equals(demoClass().getId()) && a.isRequiresDecision())
                .findFirst().orElseThrow().getId();
    }

    @Test
    @Order(1)
    void twentySevenFamiliesWithLinkedChildren() {
        long classId = demoClass().getId();
        List<User> parents = users.findByClassIdAndRoleOrderByFullName(classId, Role.PARENT);
        List<User> students = users.findByClassIdAndRoleOrderByFullName(classId, Role.STUDENT);

        assertThat(parents).hasSize(27);
        assertThat(students).hasSize(27).allSatisfy(student -> assertThat(student.getParentId()).isNotNull());
        assertThat(parents).extracting(User::getExternalId)
                .contains("vk-111", "vk-222", "demo-03", "demo-27")
                .doesNotContain("demo-01", "demo-02");
        assertThat(users.findByExternalId("demo-teacher")).get().extracting(User::getRole).isEqualTo(Role.TEACHER);
    }

    @Test
    @Order(2)
    void excursionSummaryIsTheDemoMoment() {
        String summary = announcementService.summarize(excursionId()).render();

        assertThat(summary).contains("Ответили 21 из 27 · Согласны: 18 · Не смогут: 3")
                .contains("ℹ️ Демо-аккаунтов: 25")
                .as("настоящих сбоев нет — и строки про недоставку нет").doesNotContain("Не доставлено");
    }

    @Test
    @Order(3)
    void realPhonesGotEverythingDemoAccountsGotNothing() {
        assertThat(sender.sentTo("vk-111")).extracting(FakeMessageSender.Sent::text)
                .anyMatch(text -> text.contains("Экскурсия в планетарий"))
                .anyMatch(text -> text.contains("Куда едем на экскурсию в мае?"))
                .anyMatch(text -> text.startsWith("📅 Новое событие"));
        assertThat(sender.sent).as("демо-родителям ничего не уходит")
                .noneMatch(message -> message.peerId().matches("demo-\\d+"));
        assertThat(sender.sentTo("demo-teacher")).as("сводки по 2 объявлениям и 2 опросам").hasSize(4);
    }

    @Test
    @Order(4)
    void everythingElseIsInPlace() {
        long classId = demoClass().getId();
        List<PollSummary> pollSummaries = polls.findAll().stream().map(poll -> pollService.summarize(poll.getId())).toList();

        assertThat(pollSummaries).extracting(PollSummary::answered).containsExactlyInAnyOrder(15L, 4L);
        assertThat(pollSummaries).filteredOn(s -> s.answered() == 15).singleElement()
                .satisfies(s -> assertThat(s.counts()).extracting(PollSummary.Count::count).containsExactly(7L, 5L, 3L));
        assertThat(announcements.findAll()).filteredOn(a -> !a.isRequiresDecision()).singleElement()
                .satisfies(a -> assertThat(announcementService.summarize(a.getId()).render()).contains("Прочитали 24 из 27"));
        assertThat(events.findAll()).singleElement().satisfies(e -> assertThat(e.getClassId()).isEqualTo(classId));
        assertThat(duties.count()).isEqualTo(10);
    }

    @Test
    @Order(5)
    void remindReachesOnlyRealPhonesAndSaysSoHonestly() {
        ReminderService.ReminderResult result = reminders.remindSilent("demo-teacher", SummaryTarget.announcement(excursionId()));

        assertThat(result).isEqualTo(new ReminderService.ReminderResult(2, 0, 4));
    }

    @Test
    @Order(6)
    void reportMarksDemoAccounts() {
        String csv = new String(reports.build("demo-teacher", SummaryTarget.announcement(excursionId())).content(),
                StandardCharsets.UTF_8);

        assertThat(csv).contains(";Демо-аккаунт\r\n").contains(";Доставлено").contains("Ирина Петрова;Михаил Петров;Не ответил");
    }

    @Test
    @Order(7)
    void secondRunDoesNothing() {
        long classId = demoClass().getId();

        assertThat(seedRunner.seed(false)).isEmpty();

        assertThat(classes.count()).isEqualTo(1);
        assertThat(demoClass().getId()).isEqualTo(classId);
        assertThat(users.count()).isEqualTo(55);
    }

    @Test
    @Order(8)
    void resetSeedsFromScratch() {
        long oldClassId = demoClass().getId();

        assertThat(seedRunner.seed(true)).isPresent();

        assertThat(classes.count()).isEqualTo(1);
        assertThat(demoClass().getId()).isNotEqualTo(oldClassId);
        assertThat(users.count()).isEqualTo(55);
        assertThat(announcementService.summarize(excursionId()).render()).contains("Ответили 21 из 27");
    }
}
