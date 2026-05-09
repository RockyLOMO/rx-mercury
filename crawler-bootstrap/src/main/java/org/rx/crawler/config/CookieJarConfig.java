package org.rx.crawler.config;

import org.rx.io.EntityDatabase;
import org.rx.net.http.HttpClientCookieJar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CookieJarConfig {
    @Bean
    public HttpClientCookieJar httpClientCookieJar() {
        return HttpClientCookieJar.storage(EntityDatabase.DEFAULT);
    }
}
