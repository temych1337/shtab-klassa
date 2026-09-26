package ru.shtabklassa.service;

import org.junit.jupiter.api.Test;
import ru.shtabklassa.util.SchoolTime;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderPlanTest {

    private static final Instant NOW = Instant.parse("2026-09-23T09:00:00Z"); // 12:00 по Москве

    @Test
    void eventInTwoDaysGetsBothRemindersInOrder() {
        Instant start = NOW.plus(Duration.ofDays(2));

        assertThat(ReminderPlan.triggers(start, Set.of(60, 1440), NOW)).containsExactly(
                new ReminderPlan.Trigger(1440, start.minus(Duration.ofDays(1))),
                new ReminderPlan.Trigger(60, start.minus(Duration.ofHours(1))));
    }

    @Test
    void passedRemindersAreDropped() {
        assertThat(ReminderPlan.triggers(NOW.plus(Duration.ofMinutes(90)), Set.of(60, 1440), NOW))
                .extracting(ReminderPlan.Trigger::offsetMinutes).containsExactly(60);
        assertThat(ReminderPlan.triggers(NOW.plus(Duration.ofMinutes(30)), Set.of(60, 1440), NOW)).isEmpty();
        assertThat(ReminderPlan.triggers(NOW.plus(Duration.ofMinutes(60)), Set.of(60), NOW))
                .as("ровно сейчас — уже поздно").isEmpty();
    }

    @Test
    void dutyStartsAtEightMoscowTime() {
        assertThat(SchoolTime.dutyStart(LocalDate.of(2026, 9, 28))).isEqualTo(Instant.parse("2026-09-28T05:00:00Z"));
    }

    @Test
    void dutyRemindersAreEightTheDayBeforeAndSevenOnTheDay() {
        assertThat(ReminderPlan.dutyTriggers(LocalDate.of(2026, 9, 28), NOW)).containsExactly(
                new ReminderPlan.Trigger(1440, Instant.parse("2026-09-27T05:00:00Z")),
                new ReminderPlan.Trigger(60, Instant.parse("2026-09-28T04:00:00Z")));
    }

    @Test
    void tomorrowsDutyAfterEightTodayOnlyGetsTheMorningReminder() {
        // сейчас 12:00 по Москве 23.09, дежурство 24.09: "за день" было в 08:00 - уже прошло
        assertThat(ReminderPlan.dutyTriggers(LocalDate.of(2026, 9, 24), NOW))
                .extracting(ReminderPlan.Trigger::offsetMinutes).containsExactly(60);
    }
}
