package ru.shtabklassa.adapter.vk;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.shtabklassa.adapter.MessageDeliveryException;
import ru.shtabklassa.adapter.MessageRef;
import ru.shtabklassa.util.CsvWriter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.shtabklassa.adapter.vk.VkTestSupport.form;
import static ru.shtabklassa.adapter.vk.VkTestSupport.json;

class VkDocumentUploadTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer vk;
    private VkBotAdapter adapter;

    private final byte[] report = new CsvWriter().row("Родитель", "Статус").row("Ирина", "Ответил").toBytes();

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

    private MockResponse uploadServer() {
        return json("{\"response\":{\"upload_url\":\"" + vk.url("/upload?act=add_doc") + "\"}}");
    }

    private RecordedRequest take() throws InterruptedException {
        return vk.takeRequest(1, TimeUnit.SECONDS);
    }

    @Test
    void uploadsFileSavesItAndSendsAsAttachment() throws Exception {
        vk.enqueue(uploadServer());
        vk.enqueue(json("{\"file\":\"abc|file-token\"}"));
        vk.enqueue(json("{\"response\":{\"type\":\"doc\",\"doc\":{\"id\":555,\"owner_id\":-777,\"title\":\"x\"}}}"));
        vk.enqueue(json("{\"response\":[{\"peer_id\":42,\"conversation_message_id\":17}]}"));

        MessageRef sent = adapter.sendDocument("42", "report-poll-5.csv", report, "📥 Отчёт");

        assertThat(sent).isEqualTo(new MessageRef("42", "17"));

        RecordedRequest server = take();
        assertThat(server.getPath()).isEqualTo("/method/docs.getMessagesUploadServer");
        assertThat(form(server)).containsEntry("type", "doc").containsEntry("peer_id", "42");

        RecordedRequest upload = take();
        assertThat(upload.getPath()).isEqualTo("/upload?act=add_doc");
        assertThat(upload.getHeader("Content-Type")).startsWith("multipart/form-data");
        byte[] multipart = upload.getBody().readByteArray();
        String multipartText = new String(multipart, StandardCharsets.UTF_8);
        assertThat(multipartText).contains("name=\"file\"").contains("filename=\"report-poll-5.csv\"");
        assertThat(indexOf(multipart, report)).as("файл уходит байт в байт, вместе с BOM").isNotNegative();

        RecordedRequest save = take();
        assertThat(save.getPath()).isEqualTo("/method/docs.save");
        assertThat(form(save)).containsEntry("file", "abc|file-token").containsEntry("title", "report-poll-5.csv");

        RecordedRequest send = take();
        assertThat(send.getPath()).isEqualTo("/method/messages.send");
        assertThat(form(send)).containsEntry("peer_ids", "42")
                .containsEntry("attachment", "doc-777_555")
                .containsEntry("message", "📥 Отчёт")
                .doesNotContainKey("keyboard");
    }

    @Test
    void oldArrayFormatOfDocsSaveIsAccepted() throws Exception {
        vk.enqueue(uploadServer());
        vk.enqueue(json("{\"file\":\"f\"}"));
        vk.enqueue(json("{\"response\":[{\"id\":1,\"owner_id\":2}]}"));
        vk.enqueue(json("{\"response\":[{\"peer_id\":42,\"conversation_message_id\":1}]}"));

        adapter.sendDocument("42", "r.csv", report, null);

        take();
        take();
        take();
        assertThat(form(take())).containsEntry("attachment", "doc2_1").doesNotContainKey("message");
    }

    @Test
    void uploadServerErrorStopsBeforeSave() {
        vk.enqueue(uploadServer());
        vk.enqueue(new MockResponse().setHeader("Content-Type", "text/html")
                .setBody("{\"error\":\"unknown error\",\"error_descr\":\"file type not allowed\"}"));

        assertThatThrownBy(() -> adapter.sendDocument("42", "r.csv", report, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("VK загрузка файла")
                .hasMessageContaining("file type not allowed");
        assertThat(vk.getRequestCount()).isEqualTo(2);
    }

    @Test
    void nonJsonUploadAnswerIsReported() {
        vk.enqueue(uploadServer());
        vk.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("<html>502 Bad Gateway</html>"));

        assertThatThrownBy(() -> adapter.sendDocument("42", "r.csv", report, null))
                .hasMessageContaining("ответ не JSON");
    }

    @Test
    void dropDuringUploadIsDeliveryFailure() {
        vk.enqueue(uploadServer());
        vk.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

        assertThatThrownBy(() -> adapter.sendDocument("42", "r.csv", report, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("VK загрузка файла");
    }

    @Test
    void uploadServerRefusedByVk() {
        vk.enqueue(json("{\"error\":{\"error_code\":15,\"error_msg\":\"Access denied: no access to call this method\"}}"));

        assertThatThrownBy(() -> adapter.sendDocument("42", "r.csv", report, null))
                .isInstanceOfSatisfying(VkApiException.class, e -> assertThat(e.getCode()).isEqualTo(15));
        assertThat(vk.getRequestCount()).isEqualTo(1);
    }

    @Test
    void docsSaveWithoutDocumentIsAnError() {
        vk.enqueue(uploadServer());
        vk.enqueue(json("{\"file\":\"f\"}"));
        vk.enqueue(json("{\"response\":{\"type\":\"doc\"}}"));

        assertThatThrownBy(() -> adapter.sendDocument("42", "r.csv", report, null))
                .hasMessageContaining("docs.save");
        assertThat(vk.getRequestCount()).isEqualTo(3);
    }

    @Test
    void recipientRefusalAfterUploadIsReported() {
        vk.enqueue(uploadServer());
        vk.enqueue(json("{\"file\":\"f\"}"));
        vk.enqueue(json("{\"response\":{\"type\":\"doc\",\"doc\":{\"id\":1,\"owner_id\":2}}}"));
        vk.enqueue(json("{\"response\":[{\"peer_id\":42,\"error\":{\"code\":901,\"description\":\"no permission\"}}]}"));

        assertThatThrownBy(() -> adapter.sendDocument("42", "r.csv", report, null))
                .hasMessageContaining("901");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
