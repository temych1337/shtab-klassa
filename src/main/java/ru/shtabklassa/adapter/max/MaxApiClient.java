package ru.shtabklassa.adapter.max;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import ru.shtabklassa.adapter.MessageDeliveryException;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

// https://dev.max.ru/docs-api
// токен только в Authorization - в query MAX его уже не берёт, да и в логи с url не попадёт
public class MaxApiClient {

    static final int TOO_MANY_REQUESTS = 429;

    private final RestClient http;
    private final String baseUrl;
    private final String token;
    private final ObjectMapper json;
    private final Duration rateLimitRetryDelay;

    public MaxApiClient(RestClient http, String baseUrl, String token, ObjectMapper json, Duration rateLimitRetryDelay) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.json = json;
        this.rateLimitRetryDelay = rateLimitRetryDelay;
    }

    public JsonNode get(String path, Map<String, ?> query) {
        return call(HttpMethod.GET, path, query, null);
    }

    public JsonNode post(String path, Map<String, ?> query, JsonNode body) {
        return call(HttpMethod.POST, path, query, body);
    }

    public JsonNode put(String path, Map<String, ?> query, JsonNode body) {
        return call(HttpMethod.PUT, path, query, body);
    }

    // 429 повторяем один раз
    private JsonNode call(HttpMethod method, String path, Map<String, ?> query, JsonNode body) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl + path);
        query.forEach(uri::queryParam);
        URI target = uri.encode().build().toUri();
        String name = method + " " + path;

        boolean retried = false;
        while (true) {
            try {
                RestClient.RequestBodySpec request = http.method(method).uri(target).header("Authorization", token);
                if (body != null) {
                    request.contentType(MediaType.APPLICATION_JSON).body(body.toString());
                }
                String response = request.retrieve().body(String.class);
                return response == null || response.isBlank() ? json.createObjectNode() : json.readTree(response);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == TOO_MANY_REQUESTS && !retried) {
                    retried = true;
                    sleep(rateLimitRetryDelay);
                    continue;
                }
                throw toApiException(name, e);
            } catch (RestClientException e) {
                throw new MessageDeliveryException("MAX " + name + ": " + e.getMessage(), e);
            } catch (JsonProcessingException e) {
                throw new MessageDeliveryException("MAX " + name + ": ответ не JSON", e);
            }
        }
    }

    private MaxApiException toApiException(String name, RestClientResponseException e) {
        String code = "";
        String message = e.getResponseBodyAsString();
        try {
            JsonNode error = json.readTree(message);
            code = error.path("code").asText("");
            message = error.path("message").asText(message);
        } catch (JsonProcessingException ignored) {
            // не json (502 от балансера и т.п.)
        }
        return new MaxApiException(name, e.getStatusCode().value(), code, message);
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
