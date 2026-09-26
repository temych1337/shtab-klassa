package ru.shtabklassa.adapter.vk;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
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
import static ru.shtabklassa.adapter.vk.VkTestSupport.json;

class VkLongPollTest {

    private MockWebServer vk;
    private final List<IncomingEvent> received = new CopyOnWriteArrayList<>();
    private VkLongPoll longPoll;

    @BeforeEach
    void setUp() throws Exception {
        vk = new MockWebServer();
        vk.start();
        longPoll = newLongPoll(received::add);
    }

    private VkLongPoll newLongPoll(IncomingEventListener listener) {
        VkApiClient api = new VkApiClient(VkTestSupport.restClient(Duration.ofSeconds(2)),
                vk.url("/method/").toString(), "token", "5.199", Duration.ofMillis(10));
        return new VkLongPoll(api, VkTestSupport.restClient(Duration.ofSeconds(2)), 777, 1, listener, Runnable::run);
    }

    @AfterEach
    void tearDown() throws Exception {
        longPoll.stop();
        vk.shutdown();
    }

    private MockResponse serverResponse(String key, String ts) {
        return json("{\"response\":{\"key\":\"" + key + "\",\"server\":\"" + vk.url("/lp") + "\",\"ts\":\"" + ts + "\"}}");
    }

    private static MockResponse updates(String ts, String... updates) {
        return json("{\"ts\":\"" + ts + "\",\"updates\":[" + String.join(",", updates) + "]}");
    }

    private static String callback(String eventId, int userId) {
        return """
                {"type":"message_event","event_id":"x","object":{
                  "user_id":%d,"peer_id":%d,"event_id":"%s",
                  "payload":{"a":"ANN_READ","id":5},"conversation_message_id":33}}"""
                .formatted(userId, userId, eventId);
    }

    private RecordedRequest take() throws InterruptedException {
        return vk.takeRequest(1, TimeUnit.SECONDS);
    }

    @Test
    void firstPollGetsServerThenParsesTextAndCallbackEvents() throws Exception {
        vk.enqueue(serverResponse("k1", "10"));
        vk.enqueue(updates("12",
                """
                {"type":"message_new","object":{"message":{
                  "from_id":42,"peer_id":42,"text":"Новое объявление","payload":"{\\"a\\":\\"NEW_ANN\\"}"}}}""",
                callback("ev-1", 43),
                "{\"type\":\"group_join\",\"object\":{}}"));

        assertThat(longPoll.pollOnce()).isEqualTo(2);

        assertThat(take().getPath()).isEqualTo("/method/groups.getLongPollServer");
        RecordedRequest poll = take();
        assertThat(poll.getRequestUrl().queryParameter("key")).isEqualTo("k1");
        assertThat(poll.getRequestUrl().queryParameter("ts")).isEqualTo("10");
        assertThat(poll.getRequestUrl().queryParameter("act")).isEqualTo("a_check");

        assertThat(received.get(0)).isEqualTo(
                new IncomingEvent.TextMessage("42", "42", "Новое объявление", "{\"a\":\"NEW_ANN\"}"));
        IncomingEvent.ButtonCallback button = (IncomingEvent.ButtonCallback) received.get(1);
        assertThat(button.callback()).isEqualTo(new CallbackRef("ev-1", "43", "43"));
        assertThat(button.payload()).isEqualTo("{\"a\":\"ANN_READ\",\"id\":5}");
        assertThat(button.message()).isEqualTo(new MessageRef("43", "33"));

        vk.enqueue(updates("13"));
        longPoll.pollOnce();
        assertThat(take().getRequestUrl().queryParameter("ts")).isEqualTo("12");
    }

    @Test
    void networkDropRetriesWithSameTsAndLosesNothing() throws Exception {
        vk.enqueue(serverResponse("k1", "10"));
        vk.enqueue(updates("11"));
        longPoll.pollOnce();
        take();
        take();

        vk.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        assertThatThrownBy(longPoll::pollOnce).isInstanceOf(MessageDeliveryException.class);
        take();

        vk.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(longPoll::pollOnce).isInstanceOf(MessageDeliveryException.class);
        take();

        vk.enqueue(updates("12", callback("ev-after-drop", 43)));
        assertThat(longPoll.pollOnce()).isEqualTo(1);

        RecordedRequest retry = take();
        assertThat(retry.getPath()).startsWith("/lp");
        assertThat(retry.getRequestUrl().queryParameter("ts")).isEqualTo("11");
        assertThat(received).hasSize(1);
    }

    @Test
    void failed1TakesNewTsWithoutNewKey() throws Exception {
        vk.enqueue(serverResponse("k1", "10"));
        vk.enqueue(json("{\"failed\":1,\"ts\":\"50\"}"));
        vk.enqueue(updates("51"));

        longPoll.pollOnce();
        longPoll.pollOnce();

        take();
        take();
        RecordedRequest next = take();
        assertThat(next.getPath()).startsWith("/lp");
        assertThat(next.getRequestUrl().queryParameter("ts")).isEqualTo("50");
        assertThat(next.getRequestUrl().queryParameter("key")).isEqualTo("k1");
    }

    @Test
    void failed2RefreshesKeyButKeepsTs() throws Exception {
        vk.enqueue(serverResponse("k1", "10"));
        vk.enqueue(json("{\"failed\":2}"));
        vk.enqueue(serverResponse("k2", "99"));
        vk.enqueue(updates("11"));

        longPoll.pollOnce();
        longPoll.pollOnce();

        take();
        take();
        assertThat(take().getPath()).isEqualTo("/method/groups.getLongPollServer");
        RecordedRequest next = take();
        assertThat(next.getRequestUrl().queryParameter("key")).isEqualTo("k2");
        assertThat(next.getRequestUrl().queryParameter("ts")).isEqualTo("10");
    }

    @Test
    void failed3RefreshesKeyAndTs() throws Exception {
        vk.enqueue(serverResponse("k1", "10"));
        vk.enqueue(json("{\"failed\":3}"));
        vk.enqueue(serverResponse("k2", "99"));
        vk.enqueue(updates("100"));

        longPoll.pollOnce();
        longPoll.pollOnce();

        take();
        take();
        take();
        RecordedRequest next = take();
        assertThat(next.getRequestUrl().queryParameter("key")).isEqualTo("k2");
        assertThat(next.getRequestUrl().queryParameter("ts")).isEqualTo("99");
    }

    @Test
    void failingHandlerDoesNotDropOtherEventsOrStallPolling() {
        List<IncomingEvent> survived = new CopyOnWriteArrayList<>();
        longPoll = newLongPoll(event -> {
            if (event instanceof IncomingEvent.ButtonCallback button && button.callback().eventId().equals("boom")) {
                throw new IllegalStateException("handler bug");
            }
            survived.add(event);
        });
        vk.enqueue(serverResponse("k1", "10"));
        vk.enqueue(updates("11", callback("boom", 1), callback("ok", 2)));

        assertThat(longPoll.pollOnce()).isEqualTo(2);
        assertThat(survived).hasSize(1);
    }

    // именно 502: обрыв переиспользованного сокета JDK HttpClient сам молча повторяет,
    // и до нашего backoff дело не доходит
    @Test
    void backgroundLoopBacksOffAndReconnects() throws Exception {
        vk.enqueue(serverResponse("k1", "10"));
        vk.enqueue(new MockResponse().setResponseCode(502));
        vk.enqueue(updates("11", callback("after-reconnect", 43)));

        long startedAt = System.nanoTime();
        longPoll.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 1);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
        assertThat(((IncomingEvent.ButtonCallback) received.getFirst()).callback().eventId()).isEqualTo("after-reconnect");
        take();
        take();
        assertThat(take().getRequestUrl().queryParameter("ts")).isEqualTo("10");
    }
}
