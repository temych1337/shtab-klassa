package ru.shtabklassa.adapter.vk;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.shtabklassa.adapter.MessageDeliveryException;

import java.time.Duration;
import java.util.Map;

// токен в теле POST, не в url - чтобы не светить в логах
public class VkApiClient {

    // too many requests - единственное, что есть смысл сразу повторить
    static final int TOO_MANY_REQUESTS = 6;

    private final RestClient http;
    private final String apiUrl;
    private final String token;
    private final String version;
    private final Duration rateLimitRetryDelay;

    public VkApiClient(RestClient http, String apiUrl, String token, String version, Duration rateLimitRetryDelay) {
        this.http = http;
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl : apiUrl + "/";
        this.token = token;
        this.version = version;
        this.rateLimitRetryDelay = rateLimitRetryDelay;
    }

    public JsonNode call(String method, Map<String, String> params) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        form.add("access_token", token);
        form.add("v", version);

        boolean retried = false;
        while (true) {
            JsonNode body;
            try {
                body = http.post()
                        .uri(apiUrl + method)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientException e) {
                throw new MessageDeliveryException("VK " + method + ": " + e.getMessage(), e);
            }
            if (body == null) {
                throw new MessageDeliveryException("VK " + method + ": пустой ответ");
            }

            JsonNode error = body.get("error");
            if (error == null) {
                return body.path("response");
            }
            int code = error.path("error_code").asInt();
            if (code == TOO_MANY_REQUESTS && !retried) {
                retried = true;
                sleep(rateLimitRetryDelay);
                continue;
            }
            throw new VkApiException(method, code, error.path("error_msg").asText());
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessageDeliveryException("прервано во время паузы перед повтором", e);
        }
    }
}
