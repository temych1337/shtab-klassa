package ru.shtabklassa.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.shtabklassa.service.AnnouncementService;
import ru.shtabklassa.service.PollService;
import ru.shtabklassa.service.SummaryRefresher;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SummaryRefresher summaryRefresher(AnnouncementService announcements, PollService polls,
                                      @Value("${shtab.summary.min-interval:300ms}") Duration minInterval) {
        return new SummaryRefresher(target -> {
            switch (target.kind()) {
                case ANNOUNCEMENT -> announcements.pushSummary(target.id());
                case POLL -> polls.pushSummary(target.id());
            }
        }, Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("summary-", 0).factory()), minInterval);
    }
}
