package ru.shtabklassa.service;

import ru.shtabklassa.util.SchoolTime;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * "25.09 18:30 Родительское собрание", время мск. Не угадываем - не тот формат = ошибка с примером.
 * Год можно не писать. Прошло меньше полугода назад -> скорее опечатка, ошибка. Больше (в декабре пишут 15.01) -> след. год
 */
public final class EventInput {

    public static final String EXAMPLE = "25.09 18:30 Родительское собрание";

    static final int MAX_TITLE_LENGTH = 300;
    private static final Duration RECENT_PAST = Duration.ofDays(183);
    private static final Pattern FORMAT = Pattern.compile(
            "^(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?\\s+(\\d{1,2})[:.](\\d{2})\\s+(.+)$", Pattern.DOTALL);

    public record Parsed(Instant startAt, String title) {
    }

    private EventInput() {
    }

    public static Parsed parse(String raw, Instant now) {
        Matcher match = FORMAT.matcher(raw == null ? "" : raw.strip());
        if (!match.matches()) {
            throw invalid("Не понял дату.");
        }
        String title = match.group(6).strip();
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new DomainException.InvalidInput("Название длиннее " + MAX_TITLE_LENGTH + " символов.");
        }

        int day = Integer.parseInt(match.group(1));
        int month = Integer.parseInt(match.group(2));
        int hour = Integer.parseInt(match.group(4));
        int minute = Integer.parseInt(match.group(5));
        boolean yearGiven = match.group(3) != null;
        int year = yearGiven ? Integer.parseInt(match.group(3)) : now.atZone(SchoolTime.ZONE).getYear();

        Instant startAt = toInstant(year, month, day, hour, minute);
        if (!startAt.isAfter(now)) {
            boolean longAgo = Duration.between(startAt, now).compareTo(RECENT_PAST) > 0;
            if (yearGiven || !longAgo) {
                throw invalid("Это время уже прошло.");
            }
            startAt = toInstant(year + 1, month, day, hour, minute);
        }
        return new Parsed(startAt, title);
    }

    private static Instant toInstant(int year, int month, int day, int hour, int minute) {
        try {
            return ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute), SchoolTime.ZONE).toInstant();
        } catch (DateTimeException e) {
            throw invalid("Такой даты нет.");
        }
    }

    private static DomainException.InvalidInput invalid(String reason) {
        return new DomainException.InvalidInput(reason + " Пример: " + EXAMPLE);
    }
}
