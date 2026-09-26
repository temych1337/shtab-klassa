package ru.shtabklassa.adapter.emulator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// spring отдаёт index.html сам только в корне
@Configuration
@ConditionalOnProperty(name = "emulator.enabled", havingValue = "true")
public class EmulatorPageConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/emulator/").setViewName("forward:/emulator/index.html");
        registry.addRedirectViewController("/emulator", "/emulator/");
        registry.addRedirectViewController("/", "/emulator/");
    }
}
