package org.rx.crawler.config;

import org.rx.io.EntityDatabase;
import org.rx.net.http.HttpClientCookieJar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CookieJarConfig {
    @Bean
    public EntityDatabase entityDatabase() {
        return EntityDatabase.DEFAULT;
    }

    @Bean
    public HttpClientCookieJar httpClientCookieJar(EntityDatabase entityDatabase) {
        return HttpClientCookieJar.storage(entityDatabase);
    }
}
