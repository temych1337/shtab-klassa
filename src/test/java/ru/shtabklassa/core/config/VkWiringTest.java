package ru.shtabklassa.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.adapter.vk.VkBotAdapter;
import ru.shtabklassa.adapter.vk.VkLongPoll;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "vk.enabled=true",
        "vk.token=test-token",
        "vk.group-id=1",
        "vk.api-url=http://127.0.0.1:9/method/"
})
@ActiveProfiles("test")
class VkWiringTest {

    @Autowired MessageSender sender;
    @Autowired VkLongPoll longPoll;

    @Test
    void realAdapterIsWiredAndPollerRuns() {
        assertThat(sender).isInstanceOf(VkBotAdapter.class);
        assertThat(longPoll.isRunning()).isTrue();
    }
}
