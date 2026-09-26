package ru.shtabklassa.adapter.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.shtabklassa.adapter.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/*
 * VK от имени сообщества. Вживую НЕ проверено - только MockWebServer по докам 5.199.
 * Работает ли такой бот в Сферуме (токен сообщества + bots long poll + callback кнопки) - неизвестно.
 * Писать можно только тем, кто сам написал сообществу, остальным 901 (придёт как failed).
 */
public class VkBotAdapter implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(VkBotAdapter.class);

    // лимит peer_ids в одном messages.send
    static final int MAX_PEERS_PER_SEND = 100;

    private final VkApiClient api;
    private final VkDocumentUploader documents;
    private final ObjectMapper json;

    public VkBotAdapter(VkApiClient api, VkDocumentUploader documents, ObjectMapper json) {
        this.api = api;
        this.documents = documents;
        this.json = json;
    }

    @Override
    public MessageRef sendMessage(String peerId, String text) {
        return sendKeyboard(peerId, text, null);
    }

    @Override
    public MessageRef sendKeyboard(String peerId, String text, Keyboard keyboard) {
        DeliveryResult result = sendChunk(List.of(peerId), text, keyboard, null).getFirst();
        if (!result.isDelivered()) {
            throw new MessageDeliveryException("не доставлено " + peerId + ": " + result.error());
        }
        return result.message();
    }

    @Override
    public List<DeliveryResult> sendKeyboard(List<String> peerIds, String text, Keyboard keyboard) {
        List<DeliveryResult> results = new ArrayList<>(peerIds.size());
        for (int from = 0; from < peerIds.size(); from += MAX_PEERS_PER_SEND) {
            List<String> chunk = peerIds.subList(from, Math.min(from + MAX_PEERS_PER_SEND, peerIds.size()));
            try {
                results.addAll(sendChunk(chunk, text, keyboard, null));
            } catch (MessageDeliveryException e) {
                log.warn("рассылка на {} получателей не ушла: {}", chunk.size(), e.getMessage());
                chunk.forEach(peerId -> results.add(DeliveryResult.failed(peerId, e.getMessage())));
            }
        }
        return results;
    }

    // всегда peer_ids, даже для одного: только так VK отдаёт conversation_message_id, а без него не сделать edit
    private List<DeliveryResult> sendChunk(List<String> peerIds, String text, Keyboard keyboard, String attachment) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("peer_ids", String.join(",", peerIds));
        params.put("random_id", Integer.toString(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE)));
        if (text != null) {
            params.put("message", text);
        }
        if (keyboard != null) {
            params.put("keyboard", toVkKeyboard(keyboard));
        }
        if (attachment != null) {
            params.put("attachment", attachment);
        }
        JsonNode response = api.call("messages.send", params);

        Map<String, DeliveryResult> byPeer = new HashMap<>();
        for (JsonNode item : response) {
            String peerId = item.path("peer_id").asText();
            JsonNode error = item.get("error");
            if (error != null) {
                byPeer.put(peerId, DeliveryResult.failed(peerId,
                        error.path("code").asInt() + ": " + error.path("description").asText()));
            } else {
                byPeer.put(peerId, DeliveryResult.delivered(
                        new MessageRef(peerId, item.path("conversation_message_id").asText())));
            }
        }
        return peerIds.stream()
                .map(peerId -> byPeer.getOrDefault(peerId, DeliveryResult.failed(peerId, "VK не вернул результат")))
                .toList();
    }

    @Override
    public MessageRef sendDocument(String peerId, String fileName, byte[] content, String caption) {
        String attachment = documents.upload(peerId, fileName, content);
        DeliveryResult result = sendChunk(List.of(peerId), caption, null, attachment).getFirst();
        if (!result.isDelivered()) {
            throw new MessageDeliveryException("файл не доставлен " + peerId + ": " + result.error());
        }
        return result.message();
    }

    @Override
    public void editMessage(MessageRef message, String text, Keyboard keyboard) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("peer_id", message.peerId());
        params.put("conversation_message_id", message.messageId());
        params.put("message", text);
        if (keyboard != null) {
            params.put("keyboard", toVkKeyboard(keyboard));
        }
        api.call("messages.edit", params);
    }

    @Override
    public void answerCallback(CallbackRef callback, String text) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("event_id", callback.eventId());
        params.put("user_id", callback.userId());
        params.put("peer_id", callback.peerId());
        if (text != null) {
            ObjectNode snackbar = json.createObjectNode()
                    .put("type", "show_snackbar")
                    .put("text", text);
            params.put("event_data", snackbar.toString());
        }
        api.call("messages.sendMessageEventAnswer", params);
    }

    String toVkKeyboard(Keyboard keyboard) {
        ObjectNode root = json.createObjectNode();
        root.put("inline", keyboard.inline());
        if (!keyboard.inline()) {
            root.put("one_time", false);
        }
        ArrayNode rows = root.putArray("buttons");
        for (List<Keyboard.Button> row : keyboard.rows()) {
            ArrayNode vkRow = rows.addArray();
            for (Keyboard.Button button : row) {
                ObjectNode vkButton = vkRow.addObject();
                vkButton.putObject("action")
                        .put("type", button.callback() ? "callback" : "text")
                        .put("label", button.label())
                        .put("payload", button.payload());
                vkButton.put("color", button.color().name().toLowerCase(Locale.ROOT));
            }
        }
        return root.toString();
    }
}
