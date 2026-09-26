package ru.shtabklassa.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventInputTest {

    private static final Instant NOW = Instant.parse("2026-09-23T09:00:00Z"); // 23.09, 12:00 по Москве

    @Test
    void parsesExampleFormatInMoscowTime() {
        assertThat(EventInput.parse("25.09 18:30 Родительское собрание", NOW))
                .isEqualTo(new EventInput.Parsed(Instant.parse("2026-09-25T15:30:00Z"), "Родительское собрание"));
    }

    @Test
    void acceptsYearDotInTimeAndExtraSpaces() {
        assertThat(EventInput.parse("  1.10.2026   9.05   Экскурсия в музей ", NOW))
                .isEqualTo(new EventInput.Parsed(Instant.parse("2026-10-01T06:05:00Z"), "Экскурсия в музей"));
    }

    @Test
    void laterTodayIsFine() {
        assertThat(EventInput.parse("23.09 18:00 Собрание", NOW).startAt()).isEqualTo(Instant.parse("2026-09-23T15:00:00Z"));
    }

    @Test
    void recentPastIsATypoNotNextYear() {
        assertThatThrownBy(() -> EventInput.parse("23.09 10:00 Собрание", NOW)).hasMessageStartingWith("Это время уже прошло.");
        assertThatThrownBy(() -> EventInput.parse("20.09 10:00 Собрание", NOW)).hasMessageStartingWith("Это время уже прошло.");
    }

    @Test
    void longAgoDateWithoutYearMeansNextYear() {
        assertThat(EventInput.parse("15.01 10:00 Ёлка", NOW).startAt()).isEqualTo(Instant.parse("2027-01-15T07:00:00Z"));
    }

    @Test
    void explicitPastYearIsRejected() {
        assertThatThrownBy(() -> EventInput.parse("15.01.2026 10:00 Ёлка", NOW)).hasMessageStartingWith("Это время уже прошло.");
    }

    @Test
    void impossibleDatesAreRejected() {
        assertThatThrownBy(() -> EventInput.parse("31.02 10:00 x", NOW)).hasMessageStartingWith("Такой даты нет.");
        assertThatThrownBy(() -> EventInput.parse("25.09 25:00 x", NOW)).hasMessageStartingWith("Такой даты нет.");
    }

    @Test
    void wrongFormatsExplainWithExample() {
        for (String input : new String[]{"завтра собрание", "25.09 18:30", "18:30 25.09 Собрание", "", null}) {
            assertThatThrownBy(() -> EventInput.parse(input, NOW))
                    .isInstanceOf(DomainException.InvalidInput.class)
                    .hasMessage("Не понял дату. Пример: " + EventInput.EXAMPLE);
        }
    }

    @Test
    void tooLongTitleIsRejected() {
        assertThatThrownBy(() -> EventInput.parse("25.09 18:30 " + "с".repeat(301), NOW))
                .hasMessageContaining("длиннее 300");
    }
}
