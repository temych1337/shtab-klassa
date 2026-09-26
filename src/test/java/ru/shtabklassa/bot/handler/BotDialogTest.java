package ru.shtabklassa.bot.handler;

import org.springframework.beans.factory.annotation.Autowired;
import ru.shtabklassa.adapter.CallbackRef;
import ru.shtabklassa.adapter.IncomingEvent;
import ru.shtabklassa.adapter.MessageRef;
import ru.shtabklassa.bot.keyboard.Payload;
import ru.shtabklassa.support.IntegrationTest;

import java.util.concurrent.atomic.AtomicInteger;

abstract class BotDialogTest extends IntegrationTest {

    @Autowired UpdateDispatcher dispatcher;

    private final AtomicInteger eventIds = new AtomicInteger();

    protected void text(String from, String text, Payload payload) {
        dispatcher.onEvent(new IncomingEvent.TextMessage(from, from, text, payload == null ? null : payload.toJson()));
    }

    protected void text(String from, String text) {
        text(from, text, null);
    }

    // что бот показал во всплывашке (null - без текста)
    protected String press(String from, String payloadJson) {
        String eventId = "ev-" + eventIds.incrementAndGet();
        dispatcher.onEvent(new IncomingEvent.ButtonCallback(new CallbackRef(eventId, from, from), payloadJson,
                new MessageRef(from, "555")));
        return sender.answers.stream()
                .filter(answer -> answer.callback().eventId().equals(eventId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("на нажатие " + eventId + " не ответили"))
                .text();
    }

    protected String press(String from, Payload payload) {
        return press(from, payload.toJson());
    }

    protected String lastTextTo(String peer) {
        return sender.sentTo(peer).getLast().text();
    }
}
