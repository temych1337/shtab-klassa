package ru.shtabklassa.service;

import org.junit.jupiter.api.Test;
import ru.shtabklassa.model.Role;
import ru.shtabklassa.model.User;
import ru.shtabklassa.support.FakeMessageSender;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParentBroadcasterTest {

    private final FakeMessageSender sender = new FakeMessageSender();
    private final List<User> parents = List.of(
            new User("vk-1", Role.PARENT, 1L, "Настоящий"),
            new User("demo-02", Role.PARENT, 1L, "Выдуманный"),
            new User("vk-3", Role.PARENT, 1L, "Недоступный"),
            new User(null, Role.PARENT, 1L, "Без аккаунта"));

    @Test
    void demoAccountsAreNotSentAndCountedSeparately() {
        sender.failingPeers.add("vk-3");

        ParentBroadcaster.Tally tally = ParentBroadcaster.Tally.of(new ParentBroadcaster(sender, true).send(parents, "текст", null));

        assertThat(tally).isEqualTo(new ParentBroadcaster.Tally(1, 2, 1));
        assertThat(sender.sent).extracting(FakeMessageSender.Sent::peerId).containsExactly("vk-1");
    }

    @Test
    void withoutDemoModeDemoPrefixIsJustAnId() {
        ParentBroadcaster.Tally tally = ParentBroadcaster.Tally.of(new ParentBroadcaster(sender, false).send(parents, "текст", null));

        assertThat(tally).isEqualTo(new ParentBroadcaster.Tally(3, 1, 0));
        assertThat(sender.sent).extracting(FakeMessageSender.Sent::peerId).containsExactly("vk-1", "demo-02", "vk-3");
    }
}
