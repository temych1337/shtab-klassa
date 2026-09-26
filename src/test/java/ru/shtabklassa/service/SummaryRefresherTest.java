package ru.shtabklassa.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SummaryRefresherTest {

    private static final SummaryTarget ANNOUNCEMENT_1 = SummaryTarget.announcement(1);

    private final List<SummaryTarget> pushes = new CopyOnWriteArrayList<>();
    private final SummaryRefresher refresher = new SummaryRefresher(pushes::add,
            Executors.newScheduledThreadPool(2), Duration.ofMillis(150));

    @AfterEach
    void tearDown() {
        refresher.close();
    }

    @Test
    void firstRequestIsPushedImmediately() {
        List<Long> pushedAt = new CopyOnWriteArrayList<>();
        SummaryRefresher timed = new SummaryRefresher(target -> pushedAt.add(System.nanoTime()),
                Executors.newScheduledThreadPool(1), Duration.ofSeconds(5));

        long requestedAt = System.nanoTime();
        timed.requestRefresh(ANNOUNCEMENT_1);

        // окно 5 с: если бы первый пуш ждал окна, он пришёл бы через 5 с, а не за полсекунды
        await().atMost(Duration.ofSeconds(1)).until(() -> pushedAt.size() == 1);
        assertThat(Duration.ofNanos(pushedAt.getFirst() - requestedAt)).isLessThan(Duration.ofMillis(500));
        timed.close();
    }

    @Test
    void burstCollapsesIntoLeadingAndTrailingPush() throws Exception {
        for (int i = 0; i < 50; i++) {
            refresher.requestRefresh(ANNOUNCEMENT_1);
        }

        await().atMost(Duration.ofSeconds(1)).until(() -> pushes.size() == 2);
        Thread.sleep(400);
        assertThat(pushes).containsExactly(ANNOUNCEMENT_1, ANNOUNCEMENT_1);
    }

    @Test
    void announcementAndPollWithSameIdAreDifferentTargets() {
        SummaryRefresher slowWindow = new SummaryRefresher(pushes::add,
                Executors.newScheduledThreadPool(2), Duration.ofSeconds(5));

        slowWindow.requestRefresh(ANNOUNCEMENT_1);
        slowWindow.requestRefresh(SummaryTarget.poll(1));
        slowWindow.requestRefresh(SummaryTarget.announcement(2));

        // окно 5 с: если бы они делили окно, за секунду был бы только один пуш
        await().atMost(Duration.ofSeconds(1)).until(() -> pushes.size() == 3);
        assertThat(pushes).containsExactlyInAnyOrder(ANNOUNCEMENT_1, SummaryTarget.poll(1), SummaryTarget.announcement(2));
        slowWindow.close();
    }

    @Test
    void failedPushDoesNotBlockLaterOnes() {
        List<SummaryTarget> attempts = new CopyOnWriteArrayList<>();
        SummaryRefresher flaky = new SummaryRefresher(target -> {
            attempts.add(target);
            if (attempts.size() == 1) {
                throw new IllegalStateException("VK лёг");
            }
        }, Executors.newScheduledThreadPool(1), Duration.ofMillis(50));

        flaky.requestRefresh(ANNOUNCEMENT_1);
        await().atMost(Duration.ofSeconds(1)).until(() -> attempts.size() == 1);
        await().pollDelay(Duration.ofMillis(100)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            flaky.requestRefresh(ANNOUNCEMENT_1);
            assertThat(attempts).hasSizeGreaterThanOrEqualTo(2);
        });
        flaky.close();
    }
}
