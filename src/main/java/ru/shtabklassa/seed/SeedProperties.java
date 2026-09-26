package ru.shtabklassa.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

// teacherId/parentIds - id телефонов в мессенджере (родителей до 3, остальные demo-NN)
// reset стирает ВСЮ базу, только для репетиций на локальной H2
@ConfigurationProperties("seed")
public record SeedProperties(
        @DefaultValue("demo-teacher") String teacherId,
        @DefaultValue List<String> parentIds,
        @DefaultValue("false") boolean reset) {
}
