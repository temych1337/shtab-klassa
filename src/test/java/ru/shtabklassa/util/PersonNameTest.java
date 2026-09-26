package ru.shtabklassa.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonNameTest {

    @Test
    void teacherIsAddressedByNameAndPatronymic() {
        assertThat(PersonName.address("Анна Сергеевна Козлова")).isEqualTo("Анна Сергеевна");
        assertThat(PersonName.address("  Анна   Сергеевна  Козлова ")).isEqualTo("Анна Сергеевна");
    }

    @Test
    void withoutPatronymicOnlyFirstName() {
        assertThat(PersonName.address("Ирина Петрова")).isEqualTo("Ирина");
        assertThat(PersonName.address("Ирина")).isEqualTo("Ирина");
        assertThat(PersonName.first("Михаил Петров")).isEqualTo("Михаил");
    }
}
