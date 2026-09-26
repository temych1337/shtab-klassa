package ru.shtabklassa.adapter.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.shtabklassa.adapter.vk.VkTestSupport.form;
import static ru.shtabklassa.adapter.vk.VkTestSupport.json;

class VkBotAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer vk;
    private VkBotAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        vk = new MockWebServer();
        vk.start();
        VkApiClient api = new VkApiClient(VkTestSupport.restClient(Duration.ofSeconds(2)),
                vk.url("/method/").toString(), "secret-token", "5.199", Duration.ofMillis(10));
        adapter = new VkBotAdapter(api,
                new VkDocumentUploader(api, VkTestSupport.restClient(Duration.ofSeconds(2)), mapper), mapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        vk.shutdown();
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        return vk.takeRequest(1, TimeUnit.SECONDS);
    }

    @Test
    void broadcastReportsEachRecipientSeparately() throws Exception {
        vk.enqueue(json("""
                {"response":[
                  {"peer_id":101,"message_id":0,"conversation_message_id":7},
                  {"peer_id":102,"error":{"code":901,"description":"Can't send messages for users without permission"}}
                ]}"""));
        Keyboard keyboard = Keyboard.inline(List.of(
                Keyboard.Button.callback("Прочитал", "{\"a\":\"ANN_READ\",\"id\":1}", Keyboard.Color.PRIMARY)));

        List<DeliveryResult> results = adapter.sendKeyboard(List.of("101", "102", "103"), "Собрание в четверг", keyboard);

        assertThat(results).extracting(DeliveryResult::peerId).containsExactly("101", "102", "103");
        assertThat(results.get(0).message()).isEqualTo(new MessageRef("101", "7"));
        assertThat(results.get(1).isDelivered()).isFalse();
        assertThat(results.get(1).error()).startsWith("901");
        assertThat(results.get(2).error()).isEqualTo("VK не вернул результат");

        RecordedRequest request = takeRequest();
        assertThat(request.getPath()).isEqualTo("/method/messages.send").doesNotContain("secret-token");
        Map<String, String> params = form(request);
        assertThat(params).containsEntry("peer_ids", "101,102,103")
                .containsEntry("access_token", "secret-token")
                .containsEntry("v", "5.199")
                .containsEntry("message", "Собрание в четверг")
                .containsKey("random_id");
        JsonNode vkKeyboard = mapper.readTree(params.get("keyboard"));
        assertThat(vkKeyboard.path("inline").asBoolean()).isTrue();
        JsonNode button = vkKeyboard.path("buttons").get(0).get(0);
        assertThat(button.path("action").path("type").asText()).isEqualTo("callback");
        assertThat(button.path("action").path("payload").asText()).isEqualTo("{\"a\":\"ANN_READ\",\"id\":1}");
        assertThat(button.path("color").asText()).isEqualTo("primary");
    }

    @Test
    void broadcastOverHundredPeersIsSplitIntoChunks() throws Exception {
        List<String> peers = IntStream.rangeClosed(1, 130).mapToObj(Integer::toString).toList();
        vk.enqueue(json(sendResponse(1, 100)));
        vk.enqueue(json(sendResponse(101, 130)));

        List<DeliveryResult> results = adapter.sendKeyboard(peers, "текст", null);

        assertThat(results).hasSize(130).allMatch(DeliveryResult::isDelivered);
        assertThat(form(takeRequest()).get("peer_ids").split(",")).hasSize(100);
        assertThat(form(takeRequest()).get("peer_ids").split(",")).hasSize(30);
    }

    @Test
    void failedChunkMarksItsRecipientsAsFailedWithoutThrowing() {
        List<String> peers = IntStream.rangeClosed(1, 130).mapToObj(Integer::toString).toList();
        vk.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        vk.enqueue(json(sendResponse(101, 130)));

        List<DeliveryResult> results = adapter.sendKeyboard(peers, "текст", null);

        assertThat(results.subList(0, 100)).noneMatch(DeliveryResult::isDelivered);
        assertThat(results.subList(100, 130)).allMatch(DeliveryResult::isDelivered);
    }

    @Test
    void singleSendThrowsWhenRecipientRejected() {
        vk.enqueue(json("{\"response\":[{\"peer_id\":5,\"error\":{\"code\":901,\"description\":\"nope\"}}]}"));

        assertThatThrownBy(() -> adapter.sendMessage("5", "привет"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("901");
    }

    @Test
    void editUsesConversationMessageId() throws Exception {
        vk.enqueue(json("{\"response\":1}"));

        adapter.editMessage(new MessageRef("42", "9"), "Ответили 3 из 27", null);

        RecordedRequest request = takeRequest();
        assertThat(request.getPath()).isEqualTo("/method/messages.edit");
        assertThat(form(request)).containsEntry("peer_id", "42")
                .containsEntry("conversation_message_id", "9")
                .containsEntry("message", "Ответили 3 из 27")
                .doesNotContainKey("keyboard");
    }

    @Test
    void answerCallbackSendsSnackbar() throws Exception {
        vk.enqueue(json("{\"response\":1}"));
        vk.enqueue(json("{\"response\":1}"));

        adapter.answerCallback(new CallbackRef("ev-1", "42", "42"), "✓ Отмечено");
        adapter.answerCallback(new CallbackRef("ev-2", "42", "42"), null);

        Map<String, String> withText = form(takeRequest());
        assertThat(withText).containsEntry("event_id", "ev-1").containsEntry("user_id", "42");
        JsonNode eventData = mapper.readTree(withText.get("event_data"));
        assertThat(eventData.path("type").asText()).isEqualTo("show_snackbar");
        assertThat(eventData.path("text").asText()).isEqualTo("✓ Отмечено");

        assertThat(form(takeRequest())).containsEntry("event_id", "ev-2").doesNotContainKey("event_data");
    }

    @Test
    void rateLimitErrorIsRetriedOnce() {
        vk.enqueue(json("{\"error\":{\"error_code\":6,\"error_msg\":\"Too many requests per second\"}}"));
        vk.enqueue(json("{\"response\":1}"));

        adapter.editMessage(new MessageRef("42", "9"), "текст", null);

        assertThat(vk.getRequestCount()).isEqualTo(2);
    }

    @Test
    void rateLimitErrorTwiceInARowGivesUp() {
        vk.enqueue(json("{\"error\":{\"error_code\":6,\"error_msg\":\"Too many requests per second\"}}"));
        vk.enqueue(json("{\"error\":{\"error_code\":6,\"error_msg\":\"Too many requests per second\"}}"));

        assertThatThrownBy(() -> adapter.editMessage(new MessageRef("42", "9"), "текст", null))
                .isInstanceOfSatisfying(VkApiException.class, e -> assertThat(e.getCode()).isEqualTo(6));
        assertThat(vk.getRequestCount()).isEqualTo(2);
    }

    @Test
    void otherApiErrorsAreNotRetried() {
        vk.enqueue(json("{\"error\":{\"error_code\":15,\"error_msg\":\"Access denied\"}}"));

        assertThatThrownBy(() -> adapter.answerCallback(new CallbackRef("e", "1", "1"), "x"))
                .isInstanceOfSatisfying(VkApiException.class, e -> assertThat(e.getCode()).isEqualTo(15));
        assertThat(vk.getRequestCount()).isEqualTo(1);
    }

    @Test
    void httpErrorBecomesDeliveryException() {
        vk.enqueue(new MockResponse().setResponseCode(502));

        assertThatThrownBy(() -> adapter.editMessage(new MessageRef("42", "9"), "текст", null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    private static String sendResponse(int fromPeer, int toPeer) {
        String items = String.join(",", IntStream.rangeClosed(fromPeer, toPeer)
                .mapToObj(peer -> "{\"peer_id\":" + peer + ",\"conversation_message_id\":" + peer + "}")
                .toList());
        return "{\"response\":[" + items + "]}";
    }
}
