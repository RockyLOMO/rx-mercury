package org.rx.crawler.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.config.AppConfig;
import org.rx.net.http.HttpClientCookieJar;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserService {
    final AppConfig config;
    final HttpClientCookieJar cookieJar;
    @Getter
    private BrowserPool pool;

    @PostConstruct
    public void init() {
        pool = new BrowserPool(config.getBrowser(), cookieJar);
    }
}
