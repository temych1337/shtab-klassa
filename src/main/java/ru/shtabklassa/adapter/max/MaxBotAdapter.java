package ru.shtabklassa.adapter.max;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.shtabklassa.adapter.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
 * MAX (по правилам хакатона п. 10.4.1 прототип должен работать тут). peerId = user_id, id сообщения = mid.
 * ВНИМАНИЕ: с живым токеном не гонял, только MockWebServer по докам.
 * Постоянной клавы внизу у MAX нет, поэтому меню = inline кнопки type=message (шлют свой текст).
 * Рассылки списку тоже нет - по одному.
 */
public class MaxBotAdapter implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(MaxBotAdapter.class);

    // без notification MAX на /answers отвечает 400
    static final String SILENT_ANSWER = "✓";
    private static final int ATTACHMENT_NOT_READY_RETRIES = 3;

    private final MaxApiClient api;
    private final RestClient uploadHttp;
    private final ObjectMapper json;
    private final Duration attachmentRetryDelay;

    public MaxBotAdapter(MaxApiClient api, RestClient uploadHttp, ObjectMapper json, Duration attachmentRetryDelay) {
        this.api = api;
        this.uploadHttp = uploadHttp;
        this.json = json;
        this.attachmentRetryDelay = attachmentRetryDelay;
    }

    @Override
    public MessageRef sendMessage(String peerId, String text) {
        return sendKeyboard(peerId, text, null);
    }

    @Override
    public MessageRef sendKeyboard(String peerId, String text, Keyboard keyboard) {
        ObjectNode body = json.createObjectNode().put("text", text);
        if (keyboard != null) {
            body.putArray("attachments").add(toMaxKeyboard(keyboard));
        }
        return send(peerId, body);
    }

    @Override
    public List<DeliveryResult> sendKeyboard(List<String> peerIds, String text, Keyboard keyboard) {
        List<DeliveryResult> results = new ArrayList<>(peerIds.size());
        for (String peerId : peerIds) {
            try {
                results.add(DeliveryResult.delivered(sendKeyboard(peerId, text, keyboard)));
            } catch (MessageDeliveryException e) {
                log.warn("MAX: не доставлено {}: {}", peerId, e.getMessage());
                results.add(DeliveryResult.failed(peerId, e.getMessage()));
            }
        }
        return results;
    }

    private MessageRef send(String peerId, ObjectNode body) {
        JsonNode response = api.post("/messages", Map.of("user_id", peerId), body);
        // mid в message.body.mid
        String mid = response.path("message").path("body").path("mid").asText("");
        if (mid.isEmpty()) {
            throw new MessageDeliveryException("MAX /messages: в ответе нет message.body.mid");
        }
        return new MessageRef(peerId, mid);
    }

    // пустой attachments убирает кнопки, а null в MAX значит "не трогать"
    @Override
    public void editMessage(MessageRef message, String text, Keyboard keyboard) {
        ObjectNode body = json.createObjectNode().put("text", text);
        ArrayNode attachments = body.putArray("attachments");
        if (keyboard != null) {
            attachments.add(toMaxKeyboard(keyboard));
        }
        api.put("/messages", Map.of("message_id", message.messageId()), body);
    }

    @Override
    public void answerCallback(CallbackRef callback, String text) {
        ObjectNode body = json.createObjectNode().put("notification", text == null ? SILENT_ANSWER : text);
        api.post("/answers", Map.of("callback_id", callback.eventId()), body);
    }

    // /uploads -> грузим на выданный url -> сообщение с file. на attachment.not.ready ждём и повторяем
    @Override
    public MessageRef sendDocument(String peerId, String fileName, byte[] content, String caption) {
        JsonNode slot = api.post("/uploads", Map.of("type", "file"), null);
        String uploadUrl = slot.path("url").asText("");
        if (uploadUrl.isEmpty()) {
            throw new MessageDeliveryException("MAX /uploads: нет url для загрузки");
        }
        JsonNode uploaded = upload(uploadUrl, fileName, content);
        // токен обычно в ответе загрузки, но на всякий смотрим и в /uploads
        String token = uploaded.path("token").asText(slot.path("token").asText(""));
        if (token.isEmpty()) {
            throw new MessageDeliveryException("MAX загрузка файла: нет token в ответе");
        }

        ObjectNode body = json.createObjectNode();
        if (caption != null) {
            body.put("text", caption);
        }
        body.putArray("attachments").addObject().put("type", "file").putObject("payload").put("token", token);

        Duration pause = attachmentRetryDelay;
        for (int attempt = 0; ; attempt++) {
            try {
                return send(peerId, body);
            } catch (MaxApiException e) {
                if (!"attachment.not.ready".equals(e.getCode()) || attempt >= ATTACHMENT_NOT_READY_RETRIES) {
                    throw e;
                }
                sleep(pause);
                pause = pause.multipliedBy(2);
            }
        }
    }

    private JsonNode upload(String uploadUrl, String fileName, byte[] content) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("data", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        try {
            String response = uploadHttp.post()
                    .uri(uploadUrl)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(String.class);
            return json.readTree(response == null ? "{}" : response);
        } catch (RestClientException e) {
            throw new MessageDeliveryException("MAX загрузка файла: " + e.getMessage(), e);
        } catch (JsonProcessingException e) {
            throw new MessageDeliveryException("MAX загрузка файла: ответ не JSON", e);
        }
    }

    ObjectNode toMaxKeyboard(Keyboard keyboard) {
        ObjectNode attachment = json.createObjectNode().put("type", "inline_keyboard");
        ArrayNode rows = attachment.putObject("payload").putArray("buttons");
        for (List<Keyboard.Button> row : keyboard.rows()) {
            ArrayNode maxRow = rows.addArray();
            for (Keyboard.Button button : row) {
                ObjectNode maxButton = maxRow.addObject();
                if (button.callback()) {
                    maxButton.put("type", "callback")
                            .put("text", button.label())
                            .put("payload", button.payload())
                            .put("intent", switch (button.color()) {
                                case POSITIVE -> "positive";
                                case NEGATIVE -> "negative";
                                default -> "default";
                            });
                } else {
                    // меню
                    maxButton.put("type", "message").put("text", button.label());
                }
            }
        }
        return attachment;
    }

    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessageDeliveryException("прервано во время ожидания файла", e);
        }
    }
}
