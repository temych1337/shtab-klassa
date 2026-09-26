package ru.shtabklassa.util;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

// пояс пока зашит, в бд всё UTC
public final class SchoolTime {

    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    public static final LocalTime DUTY_START = LocalTime.of(8, 0);

    public static final Set<Integer> DEFAULT_REMINDER_OFFSETS = Set.of(1440, 60);

    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EE dd.MM", RU);
    private static final DateTimeFormatter DAY_AND_TIME = DateTimeFormatter.ofPattern("EE dd.MM HH:mm", RU);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", RU);

    private SchoolTime() {
    }

    public static Instant dutyStart(LocalDate date) {
        return date.atTime(DUTY_START).atZone(ZONE).toInstant();
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    public static boolean isSchoolDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    // чт 25.09 18:30
    public static String dayAndTime(Instant instant) {
        return DAY_AND_TIME.format(instant.atZone(ZONE));
    }

    public static String day(LocalDate date) {
        return DAY.format(date);
    }

    public static String time(Instant instant) {
        return TIME.format(instant.atZone(ZONE));
    }
}
