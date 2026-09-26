package ru.shtabklassa.adapter.max;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.shtabklassa.adapter.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static ru.shtabklassa.adapter.max.MaxBotAdapterTest.json;

class MaxLongPollTest {

    private MockWebServer max;
    private final List<IncomingEvent> received = new CopyOnWriteArrayList<>();
    private MaxLongPoll longPoll;

    @BeforeEach
    void setUp() throws Exception {
        max = new MockWebServer();
        max.start();
        longPoll = newLongPoll(received::add);
    }

    private MaxLongPoll newLongPoll(IncomingEventListener listener) {
        MaxApiClient api = new MaxApiClient(MaxBotAdapterTest.restClient(), max.url("/").toString(), "bot-token",
                new ObjectMapper(), Duration.ofMillis(10));
        return new MaxLongPoll(api, 1, listener, Runnable::run);
    }

    @AfterEach
    void tearDown() throws Exception {
        longPoll.stop();
        max.shutdown();
    }

    private static MockResponse updates(Long marker, String... updates) {
        return json("{\"updates\":[" + String.join(",", updates) + "],\"marker\":" + marker + "}");
    }

    private static String callback(String callbackId, long userId) {
        return """
                {"update_type":"message_callback","timestamp":1,
                 "callback":{"timestamp":1,"callback_id":"%s","payload":"{\\"a\\":\\"ANN_READ\\",\\"id\\":5}",
                             "user":{"user_id":%d,"name":"Ирина"}},
                 "message":{"recipient":{"user_id":%d},"body":{"mid":"mid.btn","text":"Сменка"}}}"""
                .formatted(callbackId, userId, userId);
    }

    private RecordedRequest take() throws InterruptedException {
        return max.takeRequest(1, TimeUnit.SECONDS);
    }

    @Test
    void parsesTextCallbackAndBotStarted() throws Exception {
        max.enqueue(updates(501L,
                """
                {"update_type":"message_created","timestamp":1,
                 "message":{"sender":{"user_id":42,"name":"Анна"},"recipient":{"chat_id":900,"chat_type":"dialog"},
                            "body":{"mid":"mid.t","text":"📢 Новое объявление"}}}""",
                callback("cb-1", 43),
                "{\"update_type\":\"bot_started\",\"timestamp\":1,\"chat_id\":901,\"user\":{\"user_id\":44}}",
                "{\"update_type\":\"message_removed\",\"timestamp\":1}"));

        assertThat(longPoll.pollOnce()).isEqualTo(3);

        RecordedRequest request = take();
        assertThat(request.getRequestUrl().encodedPath()).isEqualTo("/updates");
        assertThat(request.getRequestUrl().queryParameter("marker")).isNull();
        assertThat(request.getRequestUrl().queryParameter("types")).isEqualTo(MaxLongPoll.TYPES);
        assertThat(request.getHeader("Authorization")).isEqualTo("bot-token");

        assertThat(received.get(0)).isEqualTo(new IncomingEvent.TextMessage("42", "42", "📢 Новое объявление", null));
        IncomingEvent.ButtonCallback button = (IncomingEvent.ButtonCallback) received.get(1);
        assertThat(button.callback()).isEqualTo(new CallbackRef("cb-1", "43", "43"));
        assertThat(button.payload()).isEqualTo("{\"a\":\"ANN_READ\",\"id\":5}");
        assertThat(button.message()).isEqualTo(new MessageRef("43", "mid.btn"));
        assertThat(received.get(2)).isEqualTo(new IncomingEvent.TextMessage("44", "44", "/start", null));

        max.enqueue(updates(502L));
        longPoll.pollOnce();
        assertThat(take().getRequestUrl().queryParameter("marker")).isEqualTo("501");
    }

    // вдруг MAX вернёт боту его же сообщение - не отвечаем
    @Test
    void messagesFromBotsAreSkipped() {
        max.enqueue(updates(601L,
                """
                {"update_type":"message_created","timestamp":1,
                 "message":{"sender":{"user_id":1000,"name":"Штаб класса","is_bot":true},
                            "recipient":{"chat_id":900,"chat_type":"dialog"},"body":{"mid":"mid.b","text":"📢 Сводка"}}}"""));

        assertThat(longPoll.pollOnce()).isZero();
        assertThat(received).isEmpty();
    }

    @Test
    void failedPollRetriesWithSameMarkerAndLosesNothing() throws Exception {
        max.enqueue(updates(10L));
        longPoll.pollOnce();
        take();

        max.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(longPoll::pollOnce).isInstanceOf(MessageDeliveryException.class);
        take();

        max.enqueue(updates(11L, callback("after-503", 7)));
        assertThat(longPoll.pollOnce()).isEqualTo(1);
        assertThat(take().getRequestUrl().queryParameter("marker")).isEqualTo("10");
        assertThat(received).hasSize(1);
    }

    @Test
    void nullMarkerKeepsThePreviousOne() throws Exception {
        max.enqueue(updates(20L));
        max.enqueue(updates(null));
        max.enqueue(updates(21L));

        longPoll.pollOnce();
        longPoll.pollOnce();
        longPoll.pollOnce();

        take();
        take();
        assertThat(take().getRequestUrl().queryParameter("marker")).isEqualTo("20");
    }

    @Test
    void failingHandlerDoesNotDropOtherEvents() {
        List<IncomingEvent> survived = new CopyOnWriteArrayList<>();
        longPoll = newLongPoll(event -> {
            if (event instanceof IncomingEvent.ButtonCallback button && button.callback().eventId().equals("boom")) {
                throw new IllegalStateException("handler bug");
            }
            survived.add(event);
        });
        max.enqueue(updates(1L, callback("boom", 1), callback("ok", 2)));

        assertThat(longPoll.pollOnce()).isEqualTo(2);
        assertThat(survived).hasSize(1);
    }

    @Test
    void backgroundLoopBacksOffAndReconnects() {
        max.enqueue(new MockResponse().setResponseCode(502));
        max.enqueue(updates(5L, callback("after-reconnect", 43)));

        long startedAt = System.nanoTime();
        longPoll.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 1);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
    }
}
