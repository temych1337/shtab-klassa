package ru.shtabklassa.adapter.vk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.shtabklassa.adapter.MessageDeliveryException;

import java.util.Map;

// docs.getMessagesUploadServer -> POST файла -> docs.save
// вживую не проверено! если токен сообщества в Сферуме так не может - отчёт упадёт с ошибкой, остальное работает
public class VkDocumentUploader {

    private final VkApiClient api;
    private final RestClient uploadHttp;
    private final ObjectMapper json;

    public VkDocumentUploader(VkApiClient api, RestClient uploadHttp, ObjectMapper json) {
        this.api = api;
        this.uploadHttp = uploadHttp;
        this.json = json;
    }

    // -> "doc{owner_id}_{id}"
    public String upload(String peerId, String fileName, byte[] content) {
        String uploadUrl = api.call("docs.getMessagesUploadServer", Map.of("type", "doc", "peer_id", peerId))
                .path("upload_url").asText();
        if (uploadUrl.isEmpty()) {
            throw new MessageDeliveryException("VK docs.getMessagesUploadServer: нет upload_url");
        }

        String file = postFile(uploadUrl, fileName, content);

        JsonNode saved = api.call("docs.save", Map.of("file", file, "title", fileName));
        // 5.199 отдаёт {"type":"doc","doc":{...}}, старые версии - массив документов
        JsonNode doc = saved.isArray() ? saved.path(0) : saved.path("doc");
        if (!doc.hasNonNull("id") || !doc.hasNonNull("owner_id")) {
            throw new MessageDeliveryException("VK docs.save: в ответе нет документа: " + saved);
        }
        return "doc" + doc.get("owner_id").asLong() + "_" + doc.get("id").asLong();
    }

    private String postFile(String uploadUrl, String fileName, byte[] content) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        // сервер загрузки не api.vk.com, ошибки там строкой и бывает text/html - парсим руками
        String body;
        try {
            body = uploadHttp.post()
                    .uri(uploadUrl)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new MessageDeliveryException("VK загрузка файла: " + e.getMessage(), e);
        }

        JsonNode response;
        try {
            response = json.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            throw new MessageDeliveryException("VK загрузка файла: ответ не JSON: " + abbreviate(body), e);
        }
        if (response == null || response.hasNonNull("error") || !response.hasNonNull("file")) {
            throw new MessageDeliveryException("VK загрузка файла: " + abbreviate(body));
        }
        return response.get("file").asText();
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "пустой ответ";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
