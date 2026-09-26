package ru.shtabklassa.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnnouncementSummaryTest {

    private static AnnouncementSummary summary(long undelivered, long demo) {
        return new AnnouncementSummary(1, "Экскурсия", true, 27, 21, 18, 3, undelivered, demo);
    }

    @Test
    void demoAccountsAloneDoNotLookLikeAnError() {
        assertThat(summary(0, 24).render())
                .endsWith("Ответили 21 из 27 · Согласны: 18 · Не смогут: 3\nℹ️ Демо-аккаунтов: 24")
                .doesNotContain("⚠");
    }

    @Test
    void realFailuresStayVisibleNextToDemoAccounts() {
        assertThat(summary(2, 24).render()).endsWith("\n⚠ Не доставлено: 2\nℹ️ Демо-аккаунтов: 24");
        assertThat(summary(2, 0).render()).endsWith("\n⚠ Не доставлено: 2");
    }

    @Test
    void cleanSummaryHasNoDeliveryLines() {
        assertThat(summary(0, 0).render()).endsWith("Ответили 21 из 27 · Согласны: 18 · Не смогут: 3");
    }
}
