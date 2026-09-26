package ru.shtabklassa.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// token брать в business.max.ru -> чат-боты
@ConfigurationProperties("max")
public record MaxProperties(
        @DefaultValue("false") boolean enabled,
        String token,
        @DefaultValue("https://platform-api2.max.ru") String apiUrl,
        @DefaultValue("30") int longPollTimeoutSeconds) {
}
