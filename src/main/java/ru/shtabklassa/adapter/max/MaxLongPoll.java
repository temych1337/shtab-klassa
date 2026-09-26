package ru.shtabklassa.adapter.max;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import ru.shtabklassa.adapter.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

// GET /updates. marker двигаем только когда весь пакет отдан в обработку - упал запрос, идём с тем же
// marker и MAX отдаёт всё заново. потерять можно только то, что уже в executor, если процесс умрёт
public class MaxLongPoll implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MaxLongPoll.class);

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    static final String TYPES = "message_created,message_callback,bot_started";

    private final MaxApiClient api;
    private final int timeoutSeconds;
    private final IncomingEventListener listener;
    private final Executor dispatcher;

    // только из потока поллинга
    private Long marker;

    private volatile boolean running;
    private Thread pollThread;

    public MaxLongPoll(MaxApiClient api, int timeoutSeconds, IncomingEventListener listener, Executor dispatcher) {
        this.api = api;
        this.timeoutSeconds = timeoutSeconds;
        this.listener = listener;
        this.dispatcher = dispatcher;
    }

    int pollOnce() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("timeout", timeoutSeconds);
        query.put("types", TYPES);
        if (marker != null) {
            query.put("marker", marker);
        }
        JsonNode body = api.get("/updates", query);

        int dispatched = 0;
        for (JsonNode update : body.path("updates")) {
            IncomingEvent event = toEvent(update);
            if (event != null) {
                dispatch(event);
                dispatched++;
            }
        }
        JsonNode next = body.get("marker");
        if (next != null && !next.isNull()) {
            marker = next.asLong();
        }
        return dispatched;
    }

    private static IncomingEvent toEvent(JsonNode update) {
        return switch (update.path("update_type").asText()) {
            case "message_created" -> {
                JsonNode message = update.path("message");
                // по докам своё бот не получает, но проверить вживую не на чем - перестрахуемся
                if (message.path("sender").path("is_bot").asBoolean(false)) {
                    yield null;
                }
                String userId = message.path("sender").path("user_id").asText();
                // пишем в личку по user_id, peer = отправитель
                yield new IncomingEvent.TextMessage(userId, userId, message.path("body").path("text").asText(""), null);
            }
            case "message_callback" -> {
                JsonNode callback = update.path("callback");
                String userId = callback.path("user").path("user_id").asText();
                String mid = update.path("message").path("body").path("mid").asText("");
                yield new IncomingEvent.ButtonCallback(
                        new CallbackRef(callback.path("callback_id").asText(), userId, userId),
                        callback.path("payload").isNull() ? null : callback.path("payload").asText(null),
                        mid.isEmpty() ? null : new MessageRef(userId, mid));
            }
            // "Начать" в новом диалоге
            case "bot_started" -> {
                String userId = update.path("user").path("user_id").asText();
                yield new IncomingEvent.TextMessage(userId, userId, "/start", null);
            }
            default -> null;
        };
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
                log.warn("MAX long poll упал, повтор через {} с: {}", backoff.toSeconds(), e.getMessage());
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
        pollThread = Thread.ofPlatform().name("max-long-poll").daemon().start(this::loop);
        log.info("MAX long poll запущен");
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
