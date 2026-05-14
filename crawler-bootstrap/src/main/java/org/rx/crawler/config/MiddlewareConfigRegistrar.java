package org.rx.crawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.service.MiddlewareConfig;

@Configuration
public class MiddlewareConfigRegistrar {
    @Bean
    @ConfigurationProperties(prefix = "app")
    public MiddlewareConfig middlewareConfig() {
        return new MiddlewareConfig();
    }
}
