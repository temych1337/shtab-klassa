package ru.shtabklassa.adapter.vk;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import ru.shtabklassa.adapter.*;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;

/*
 * Bots Long Poll. ts двигаем только после того как весь пакет отдан в обработку, так что при обрыве
 * (сеть/таймаут/5xx) идём с тем же ts и VK отдаёт те же события. key не сбрасываем, протухший VK сам скажет failed=2.
 * Где всё-таки теряем: failed=1/3 (VK сам потерял историю) и то, что уже в executor, если процесс упал.
 */
public class VkLongPoll implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(VkLongPoll.class);

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final VkApiClient api;
    private final RestClient longPollHttp;
    private final long groupId;
    private final int waitSeconds;
    private final IncomingEventListener listener;
    private final Executor dispatcher;

    // только поток поллинга (ну и тесты)
    private String server;
    private String key;
    private String ts;

    private volatile boolean running;
    private Thread pollThread;

    public VkLongPoll(VkApiClient api, RestClient longPollHttp, long groupId, int waitSeconds,
                      IncomingEventListener listener, Executor dispatcher) {
        this.api = api;
        this.longPollHttp = longPollHttp;
        this.groupId = groupId;
        this.waitSeconds = waitSeconds;
        this.listener = listener;
        this.dispatcher = dispatcher;
    }

    // упало -> ts не тронут, можно повторять
    int pollOnce() {
        if (key == null) {
            refreshServer(ts == null);
        }
        JsonNode body = fetchUpdates();

        JsonNode failed = body.get("failed");
        if (failed != null) {
            switch (failed.asInt()) {
                case 1 -> {
                    log.warn("long poll: VK потерял часть истории, продолжаем с ts={}", body.path("ts").asText());
                    ts = body.path("ts").asText();
                }
                case 2 -> refreshServer(false);
                case 3 -> {
                    log.warn("long poll: VK потерял историю, берём новый ts");
                    refreshServer(true);
                }
                default -> throw new MessageDeliveryException("long poll: неизвестный failed=" + failed.asText());
            }
            return 0;
        }

        int dispatched = 0;
        for (JsonNode update : body.path("updates")) {
            IncomingEvent event = toEvent(update);
            if (event != null) {
                dispatch(event);
                dispatched++;
            }
        }
        ts = body.path("ts").asText();
        return dispatched;
    }

    private void refreshServer(boolean takeNewTs) {
        var response = api.call("groups.getLongPollServer", Map.of("group_id", Long.toString(groupId)));
        server = response.path("server").asText();
        key = response.path("key").asText();
        if (takeNewTs || ts == null) {
            ts = response.path("ts").asText();
        }
    }

    private JsonNode fetchUpdates() {
        URI uri = UriComponentsBuilder.fromUriString(server)
                .queryParam("act", "a_check")
                .queryParam("key", key)
                .queryParam("ts", ts)
                .queryParam("wait", waitSeconds)
                .encode()
                .build()
                .toUri();
        try {
            JsonNode body = longPollHttp.get().uri(uri).retrieve().body(JsonNode.class);
            if (body == null) {
                throw new MessageDeliveryException("long poll: пустой ответ");
            }
            return body;
        } catch (RestClientException e) {
            throw new MessageDeliveryException("long poll: " + e.getMessage(), e);
        }
    }

    private IncomingEvent toEvent(JsonNode update) {
        JsonNode object = update.path("object");
        return switch (update.path("type").asText()) {
            case "message_new" -> {
                JsonNode message = object.path("message");
                yield new IncomingEvent.TextMessage(
                        message.path("from_id").asText(),
                        message.path("peer_id").asText(),
                        message.path("text").asText(""),
                        payloadOf(message.get("payload")));
            }
            case "message_event" -> {
                String peerId = object.path("peer_id").asText();
                JsonNode cmid = object.get("conversation_message_id");
                yield new IncomingEvent.ButtonCallback(
                        new CallbackRef(object.path("event_id").asText(), object.path("user_id").asText(), peerId),
                        payloadOf(object.get("payload")),
                        cmid == null ? null : new MessageRef(peerId, cmid.asText()));
            }
            default -> null;
        };
    }

    // в message_new payload строкой, в message_event объектом. спасибо VK
    private static String payloadOf(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        return payload.isTextual() ? payload.asText() : payload.toString();
    }

    private void dispatch(IncomingEvent event) {
        dispatcher.execute(() -> {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                log.error("обработка события {} упала", event, e);
            }
        });
    }

    private void loop() {
        Duration backoff = INITIAL_BACKOFF;
        while (running) {
            try {
                pollOnce();
                backoff = INITIAL_BACKOFF;
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("long poll упал, повтор через {} с: {}", backoff.toSeconds(), e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    @Override
    public void start() {
        running = true;
        pollThread = Thread.ofPlatform().name("vk-long-poll").daemon().start(this::loop);
        log.info("VK long poll запущен, group_id={}", groupId);
    }

    @Override
    public void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
