package ru.shtabklassa.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.adapter.max.MaxBotAdapter;
import ru.shtabklassa.adapter.max.MaxLongPoll;

import static org.assertj.core.api.Assertions.assertThat;

// сам MAX тут недоступен, проверяем только что контекст собрался и поллинг стартанул
@SpringBootTest(properties = {
        "max.enabled=true",
        "max.token=test-token",
        "max.api-url=http://127.0.0.1:9"
})
@ActiveProfiles("test")
class MaxWiringTest {

    @Autowired MessageSender sender;
    @Autowired MaxLongPoll longPoll;

    @Test
    void maxAdapterIsWiredAndPollerRuns() {
        assertThat(sender).isInstanceOf(MaxBotAdapter.class);
        assertThat(longPoll.isRunning()).isTrue();
    }
}
