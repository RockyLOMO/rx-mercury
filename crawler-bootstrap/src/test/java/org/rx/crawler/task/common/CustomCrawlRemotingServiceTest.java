package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.net.http.HttpClientCookieJar;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CustomCrawlRemotingServiceTest {
    @Test
    public void cookiesRawShouldReturnEmptyWhenUrlIsBlank() {
        CustomCrawlRemotingService service = new CustomCrawlRemotingService(
                new AppConfig(), null, null, null, null, HttpClientCookieJar.memory());

        assertEquals("", service.cookiesRaw(null, null));
        assertEquals("", service.cookiesRaw(null, ""));
    }

    @Test
    public void cookiesRawShouldReturnCookieFromJar() {
        HttpClientCookieJar cookieJar = HttpClientCookieJar.memory();
        cookieJar.saveRawCookie(URI.create("https://example.com/path"), "sid=123; token=abc");
        CustomCrawlRemotingService service = new CustomCrawlRemotingService(
                new AppConfig(), null, null, null, null, cookieJar);

        assertEquals("sid=123; token=abc", service.cookiesRaw(null, "https://example.com/path"));
    }

    @Test
    public void cookiesRawShouldReturnEmptyWhenUrlIsInvalid() {
        CustomCrawlRemotingService service = new CustomCrawlRemotingService(
                new AppConfig(), null, null, null, null, HttpClientCookieJar.memory());

        assertEquals("", service.cookiesRaw(null, "://bad_url"));
    }
}
