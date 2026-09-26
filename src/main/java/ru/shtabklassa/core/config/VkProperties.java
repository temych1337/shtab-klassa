package ru.shtabklassa.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// токен сообщества с правом messages, groupId без минуса
@ConfigurationProperties("vk")
public record VkProperties(
        @DefaultValue("false") boolean enabled,
        String token,
        long groupId,
        @DefaultValue("https://api.vk.com/method/") String apiUrl,
        @DefaultValue("5.199") String apiVersion,
        @DefaultValue("25") int longPollWaitSeconds) {
}
