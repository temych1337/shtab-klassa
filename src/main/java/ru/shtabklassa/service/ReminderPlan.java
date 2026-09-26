package ru.shtabklassa.service;

import ru.shtabklassa.util.SchoolTime;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

// отдельно от Quartz чтобы гонять юнит-тестами
public final class ReminderPlan {

    public record Trigger(int offsetMinutes, Instant fireAt) {
    }

    private ReminderPlan() {
    }

    // прошедшие не догоняем: "за час" через секунду после создания события выглядит как баг
    public static List<Trigger> triggers(Instant startAt, Collection<Integer> offsetsMinutes, Instant now) {
        return offsetsMinutes.stream()
                .map(offset -> new Trigger(offset, startAt.minus(Duration.ofMinutes(offset))))
                .filter(trigger -> trigger.fireAt().isAfter(now))
                .sorted(Comparator.comparing(Trigger::fireAt))
                .toList();
    }

    // дежурство в 8:00 мск -> напоминания в 8:00 накануне и в 7:00
    public static List<Trigger> dutyTriggers(LocalDate date, Instant now) {
        return triggers(SchoolTime.dutyStart(date), SchoolTime.DEFAULT_REMINDER_OFFSETS, now);
    }
}
