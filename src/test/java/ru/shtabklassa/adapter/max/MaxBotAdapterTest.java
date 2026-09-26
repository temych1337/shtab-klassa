package ru.shtabklassa.adapter.max;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ru.shtabklassa.adapter.*;
import ru.shtabklassa.util.CsvWriter;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// только MockWebServer, живой MAX не проверяли
class MaxBotAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer max;
    private MaxBotAdapter adapter;

    static RestClient restClient() {
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().requestFactory(factory).build();
    }

    static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    static MockResponse sent(String mid) {
        return json("{\"message\":{\"recipient\":{\"user_id\":42},\"body\":{\"mid\":\"" + mid + "\",\"text\":\"x\"}}}");
    }

    @BeforeEach
    void setUp() throws Exception {
        max = new MockWebServer();
        max.start();
        MaxApiClient api = new MaxApiClient(restClient(), max.url("/").toString(), "bot-token", mapper, Duration.ofMillis(10));
        adapter = new MaxBotAdapter(api, restClient(), mapper, Duration.ofMillis(10));
    }

    @AfterEach
    void tearDown() throws Exception {
        max.shutdown();
    }

    private RecordedRequest take() throws InterruptedException {
        return max.takeRequest(1, TimeUnit.SECONDS);
    }

    private JsonNode body(RecordedRequest request) throws Exception {
        return mapper.readTree(request.getBody().readUtf8());
    }

    @Test
    void sendsTextWithCallbackKeyboardAndReturnsMid() throws Exception {
        max.enqueue(sent("mid.00a1"));
        Keyboard keyboard = Keyboard.inline(List.of(
                Keyboard.Button.callback("Согласен", "{\"a\":\"ANN_AGREE\",\"id\":5}", Keyboard.Color.POSITIVE),
                Keyboard.Button.callback("Не смогу", "{\"a\":\"ANN_DECLINE\",\"id\":5}", Keyboard.Color.NEGATIVE)));

        MessageRef ref = adapter.sendKeyboard("42", "Экскурсия", keyboard);

        assertThat(ref).isEqualTo(new MessageRef("42", "mid.00a1"));
        RecordedRequest request = take();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/messages?user_id=42");
        assertThat(request.getHeader("Authorization")).isEqualTo("bot-token");
        JsonNode body = body(request);
        assertThat(body.path("text").asText()).isEqualTo("Экскурсия");
        JsonNode attachment = body.path("attachments").get(0);
        assertThat(attachment.path("type").asText()).isEqualTo("inline_keyboard");
        JsonNode agree = attachment.path("payload").path("buttons").get(0).get(0);
        assertThat(agree.path("type").asText()).isEqualTo("callback");
        assertThat(agree.path("text").asText()).isEqualTo("Согласен");
        assertThat(agree.path("payload").asText()).isEqualTo("{\"a\":\"ANN_AGREE\",\"id\":5}");
        assertThat(agree.path("intent").asText()).isEqualTo("positive");
        assertThat(attachment.path("payload").path("buttons").get(0).get(1).path("intent").asText()).isEqualTo("negative");
    }

    @Test
    void menuButtonsBecomeMessageButtons() throws Exception {
        max.enqueue(sent("mid.1"));

        adapter.sendKeyboard("42", "Меню", Keyboard.menu(List.of(
                Keyboard.Button.text("📢 Новое объявление", "{\"a\":\"NEW_ANN\"}", Keyboard.Color.PRIMARY))));

        JsonNode button = body(take()).path("attachments").get(0).path("payload").path("buttons").get(0).get(0);
        assertThat(button.path("type").asText()).isEqualTo("message");
        assertThat(button.path("text").asText()).isEqualTo("📢 Новое объявление");
        assertThat(button.has("payload")).isFalse();
    }

    @Test
    void broadcastGoesOneByOneAndOneFailureDoesNotStopOthers() {
        max.enqueue(sent("mid.1"));
        max.enqueue(json("{\"code\":\"chat.denied\",\"message\":\"user blocked the bot\"}").setResponseCode(403));
        max.enqueue(sent("mid.3"));

        List<DeliveryResult> results = adapter.sendKeyboard(List.of("1", "2", "3"), "текст", null);

        assertThat(results).extracting(DeliveryResult::isDelivered).containsExactly(true, false, true);
        assertThat(results.get(1).error()).contains("403").contains("chat.denied");
        assertThat(results.get(2).message()).isEqualTo(new MessageRef("3", "mid.3"));
    }

    @Test
    void editWithoutKeyboardRemovesButtons() throws Exception {
        max.enqueue(json("{\"success\":true}"));

        adapter.editMessage(new MessageRef("42", "mid.77"), "Ответили 3 из 27", null);

        RecordedRequest request = take();
        assertThat(request.getMethod()).isEqualTo("PUT");
        assertThat(request.getPath()).isEqualTo("/messages?message_id=mid.77");
        JsonNode body = body(request);
        assertThat(body.path("text").asText()).isEqualTo("Ответили 3 из 27");
        assertThat(body.path("attachments").isArray()).isTrue();
        assertThat(body.path("attachments")).isEmpty();
    }

    @Test
    void callbackAnswerAlwaysCarriesNotification() throws Exception {
        max.enqueue(json("{\"success\":true}"));
        max.enqueue(json("{\"success\":true}"));

        adapter.answerCallback(new CallbackRef("cb-1", "42", "42"), "✓ Отмечено");
        adapter.answerCallback(new CallbackRef("cb-2", "42", "42"), null);

        RecordedRequest first = take();
        assertThat(first.getPath()).isEqualTo("/answers?callback_id=cb-1");
        assertThat(body(first).path("notification").asText()).isEqualTo("✓ Отмечено");
        assertThat(body(take()).path("notification").asText()).isEqualTo(MaxBotAdapter.SILENT_ANSWER);
    }

    @Test
    void rateLimitIsRetriedOnce() {
        max.enqueue(new MockResponse().setResponseCode(429));
        max.enqueue(sent("mid.1"));

        assertThat(adapter.sendMessage("42", "текст").messageId()).isEqualTo("mid.1");
        assertThat(max.getRequestCount()).isEqualTo(2);
    }

    @Test
    void responseWithoutMidIsAnError() {
        max.enqueue(json("{\"message\":{}}"));

        assertThatThrownBy(() -> adapter.sendMessage("42", "текст")).hasMessageContaining("mid");
    }

    @Test
    void documentGoesThroughUploadsThenMessage() throws Exception {
        byte[] report = new CsvWriter().row("Родитель", "Статус").toBytes();
        max.enqueue(json("{\"url\":\"" + max.url("/upload-here") + "\"}"));
        max.enqueue(json("{\"token\":\"file-token\"}"));
        max.enqueue(json("{\"code\":\"attachment.not.ready\",\"message\":\"Key: errors.process.attachment.file.not.processed\"}")
                .setResponseCode(400));
        max.enqueue(sent("mid.doc"));

        MessageRef ref = adapter.sendDocument("42", "report.csv", report, "📥 Отчёт");

        assertThat(ref.messageId()).isEqualTo("mid.doc");
        assertThat(take().getPath()).isEqualTo("/uploads?type=file");
        RecordedRequest upload = take();
        assertThat(upload.getPath()).isEqualTo("/upload-here");
        String multipart = upload.getBody().readString(StandardCharsets.UTF_8);
        assertThat(multipart).contains("name=\"data\"").contains("filename=\"report.csv\"");
        take();
        JsonNode message = body(take());
        assertThat(message.path("text").asText()).isEqualTo("📥 Отчёт");
        JsonNode file = message.path("attachments").get(0);
        assertThat(file.path("type").asText()).isEqualTo("file");
        assertThat(file.path("payload").path("token").asText()).isEqualTo("file-token");
    }

    @Test
    void otherSendErrorsOnDocumentAreNotRetried() {
        max.enqueue(json("{\"url\":\"" + max.url("/upload-here") + "\"}"));
        max.enqueue(json("{\"token\":\"t\"}"));
        max.enqueue(json("{\"code\":\"chat.denied\",\"message\":\"no\"}").setResponseCode(403));

        assertThatThrownBy(() -> adapter.sendDocument("42", "r.csv", new byte[]{1}, null))
                .isInstanceOfSatisfying(MaxApiException.class, e -> assertThat(e.getCode()).isEqualTo("chat.denied"));
        assertThat(max.getRequestCount()).isEqualTo(3);
    }
}
