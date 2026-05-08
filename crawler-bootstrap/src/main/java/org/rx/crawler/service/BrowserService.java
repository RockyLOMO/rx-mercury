package org.rx.crawler.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.BrowserAsyncTopic;
import org.rx.crawler.config.AppConfig;
import org.rx.net.rpc.Remoting;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

import static org.rx.core.Extends.quietly;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserService {
    final AppConfig config;
    final BrowserAsyncTopic asyncTopic;
    @Getter
    private BrowserPool pool;

    @PostConstruct
    public void init() {
        quietly(() -> Remoting.register(pool = new BrowserPool(config.getBrowser(), asyncTopic), pool.conf.getListenPort(), false));
    }
}
