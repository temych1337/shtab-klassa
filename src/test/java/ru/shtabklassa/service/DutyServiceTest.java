package ru.shtabklassa.service;

import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.model.DutySchedule;
import ru.shtabklassa.model.User;
import ru.shtabklassa.repository.DutyScheduleRepository;
import ru.shtabklassa.repository.ReminderLogRepository.TargetKind;
import ru.shtabklassa.support.IntegrationTest;
import ru.shtabklassa.util.SchoolTime;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DutyServiceTest extends IntegrationTest {

    @Autowired DutyService dutyService;
    @Autowired DutyScheduleRepository duties;

    private List<DutySchedule> allDuties() {
        return duties.findAll().stream().sorted((a, b) -> a.getDate().compareTo(b.getDate())).toList();
    }

    @Test
    void fillsTenWeekdaysRotatingAlphabetically() {
        TestClass c = createClass("11a", 3);
        List<User> students = addStudents(c, "Вася", "Аня", "Боря");

        DutyService.PlanResult result = dutyService.planTwoWeeks("t-11a");

        assertThat(result).isEqualTo(new DutyService.PlanResult(10, 10));
        List<DutySchedule> plan = allDuties();
        LocalDate tomorrow = SchoolTime.today(clock).plusDays(1);
        assertThat(plan).allSatisfy(duty -> {
            assertThat(SchoolTime.isSchoolDay(duty.getDate())).isTrue();
            assertThat(duty.getDate()).isBetween(tomorrow, tomorrow.plusDays(13));
        });
        long anya = students.get(1).getId();
        long borya = students.get(2).getId();
        long vasya = students.get(0).getId();
        assertThat(plan).extracting(DutySchedule::getStudentId)
                .containsExactly(anya, borya, vasya, anya, borya, vasya, anya, borya, vasya, anya);
    }

    @Test
    void secondClickAddsNothing() {
        TestClass c = createClass("11b", 2);
        addStudents(c, "Аня", "Боря");
        dutyService.planTwoWeeks("t-11b");

        assertThat(dutyService.planTwoWeeks("t-11b")).isEqualTo(new DutyService.PlanResult(0, 10));
        assertThat(duties.count()).isEqualTo(10);
    }

    @Test
    void simultaneousClicksDoNotDuplicateDays() {
        TestClass c = createClass("11c", 2);
        addStudents(c, "Аня", "Боря");

        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<DutyService.PlanResult>> clicks = List.of(
                CompletableFuture.supplyAsync(() -> planAfter(start, "t-11c")),
                CompletableFuture.supplyAsync(() -> planAfter(start, "t-11c")));
        start.countDown();

        int created = clicks.stream().mapToInt(click -> click.join().created()).sum();
        assertThat(created).isEqualTo(10);
        assertThat(duties.count()).isEqualTo(10);
    }

    private DutyService.PlanResult planAfter(CountDownLatch start, String teacher) {
        try {
            start.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return dutyService.planTwoWeeks(teacher);
    }

    @Test
    void nextWeekContinuesRotationFromLastStudent() {
        TestClass c = createClass("11d", 3);
        addStudents(c, "Аня", "Боря", "Вася");
        dutyService.planTwoWeeks("t-11d");
        DutySchedule lastBefore = allDuties().getLast();

        clock.advance(Duration.ofDays(7));
        DutyService.PlanResult result = dutyService.planTwoWeeks("t-11d");

        assertThat(result.created()).isEqualTo(5);
        List<DutySchedule> added = allDuties().stream().filter(duty -> duty.getDate().isAfter(lastBefore.getDate())).toList();
        int lastPosition = lastBefore.getRotationOrder();
        assertThat(added.getFirst().getRotationOrder()).isEqualTo((lastPosition + 1) % 3);
    }

    @Test
    void remindersAreScheduledForPlannedDuties() throws Exception {
        TestClass c = createClass("11e", 1);
        addStudents(c, "Аня");
        dutyService.planTwoWeeks("t-11e");

        DutySchedule last = allDuties().getLast();
        Set<JobKey> jobs = quartz.getJobKeys(GroupMatcher.jobGroupEquals(CalendarReminderScheduler.GROUP));
        assertThat(jobs).contains(
                CalendarReminderScheduler.jobKey(TargetKind.DUTY, last.getId(), 1440),
                CalendarReminderScheduler.jobKey(TargetKind.DUTY, last.getId(), 60));
    }

    @Test
    void classWithoutStudentsAndNonTeachersAreRejected() {
        createClass("11f", 1);

        assertThatThrownBy(() -> dutyService.planTwoWeeks("t-11f"))
                .isInstanceOf(DomainException.InvalidInput.class)
                .hasMessageContaining("нет учеников");
        assertThatThrownBy(() -> dutyService.planTwoWeeks("p-11f-1")).isInstanceOf(DomainException.NotAllowed.class);
        assertThat(duties.count()).isZero();
    }

    @Test
    void overviewListsNames() {
        TestClass c = createClass("11g", 1);
        addStudents(c, "Аня Смирнова");

        assertThat(dutyService.overview("t-11g")).isEqualTo("🧹 График дежурств пуст.");
        dutyService.planTwoWeeks("t-11g");
        assertThat(dutyService.overview("t-11g")).startsWith("🧹 Дежурства:").contains("— Аня Смирнова");
    }
}
