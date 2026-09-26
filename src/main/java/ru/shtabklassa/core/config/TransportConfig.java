package ru.shtabklassa.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ru.shtabklassa.adapter.LoggingMessageSender;
import ru.shtabklassa.adapter.MessageSender;
import ru.shtabklassa.adapter.emulator.EmulatorMessageSender;
import ru.shtabklassa.adapter.max.MaxApiClient;
import ru.shtabklassa.adapter.max.MaxBotAdapter;
import ru.shtabklassa.adapter.max.MaxLongPoll;
import ru.shtabklassa.adapter.vk.VkApiClient;
import ru.shtabklassa.adapter.vk.VkBotAdapter;
import ru.shtabklassa.adapter.vk.VkDocumentUploader;
import ru.shtabklassa.adapter.vk.VkLongPoll;
import ru.shtabklassa.bot.handler.UpdateDispatcher;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// max.enabled (основной) / vk.enabled (запасной) / emulator.enabled, ничего - просто лог
@Configuration
public class TransportConfig {

    @Configuration
    @ConditionalOnProperty(name = "max.enabled", havingValue = "true")
    static class Max {

        @Bean
        MaxApiClient maxApiClient(MaxProperties max, VkProperties vk, ObjectMapper json) {
            if (vk.enabled()) {
                throw new IllegalStateException("включены и max.enabled, и vk.enabled, оставь один транспорт");
            }
            if (max.token() == null || max.token().isBlank()) {
                throw new IllegalStateException("max.enabled=true, но не задан max.token (см. application-local.yml.example)");
            }
            return new MaxApiClient(restClient(Duration.ofSeconds(max.longPollTimeoutSeconds() + 10L)),
                    max.apiUrl(), max.token(), json, Duration.ofSeconds(1));
        }

        @Bean
        MessageSender maxBotAdapter(MaxApiClient api, ObjectMapper json) {
            return new MaxBotAdapter(api, restClient(Duration.ofSeconds(30)), json, Duration.ofMillis(500));
        }

        @Bean(destroyMethod = "shutdown")
        ExecutorService maxEventExecutor() {
            return Executors.newFixedThreadPool(4, Thread.ofPlatform().name("max-event-", 0).factory());
        }

        @Bean
        MaxLongPoll maxLongPoll(MaxProperties max, MaxApiClient api, UpdateDispatcher dispatcher,
                                ExecutorService maxEventExecutor) {
            return new MaxLongPoll(api, max.longPollTimeoutSeconds(), dispatcher, maxEventExecutor);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "vk.enabled", havingValue = "true")
    static class Vk {

        @Bean
        VkApiClient vkApiClient(VkProperties vk) {
            if (vk.token() == null || vk.token().isBlank() || vk.groupId() <= 0) {
                throw new IllegalStateException("vk.enabled=true, но не заданы vk.token / vk.group-id (см. application-local.yml.example)");
            }
            return new VkApiClient(restClient(Duration.ofSeconds(10)), vk.apiUrl(), vk.token(), vk.apiVersion(),
                    Duration.ofMillis(400));
        }

        @Bean
        MessageSender vkBotAdapter(VkApiClient api, ObjectMapper json) {
            VkDocumentUploader documents = new VkDocumentUploader(api, restClient(Duration.ofSeconds(30)), json);
            return new VkBotAdapter(api, documents, json);
        }

        // отдельно от потока поллинга, чтобы долгая рассылка не тормозила приём кликов
        @Bean(destroyMethod = "shutdown")
        ExecutorService vkEventExecutor() {
            return Executors.newFixedThreadPool(4, Thread.ofPlatform().name("vk-event-", 0).factory());
        }

        @Bean
        VkLongPoll vkLongPoll(VkProperties vk, VkApiClient api, UpdateDispatcher dispatcher, ExecutorService vkEventExecutor) {
            RestClient longPollHttp = restClient(Duration.ofSeconds(vk.longPollWaitSeconds() + 10L));
            return new VkLongPoll(api, longPollHttp, vk.groupId(), vk.longPollWaitSeconds(), dispatcher, vkEventExecutor);
        }
    }

    @Bean
    @ConditionalOnProperty(name = "emulator.enabled", havingValue = "true")
    EmulatorMessageSender emulatorMessageSender(MaxProperties max, VkProperties vk) {
        if (max.enabled() || vk.enabled()) {
            throw new IllegalStateException("emulator.enabled вместе с MAX/VK, оставь один транспорт");
        }
        return new EmulatorMessageSender();
    }

    @Bean
    @ConditionalOnExpression("!${vk.enabled:false} && !${max.enabled:false} && !${emulator.enabled:false}")
    MessageSender loggingMessageSender() {
        return new LoggingMessageSender();
    }

    static RestClient restClient(Duration readTimeout) {
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }
}
