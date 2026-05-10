package org.rx.crawler.service;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.exception.TraceHandler;
import org.rx.net.http.HttpClientCookieJar;
import org.rx.util.BeanMapper;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.Extends.quietly;
import static org.rx.core.Extends.tryClose;

@Slf4j
public final class BrowserPool extends Disposable {
    final class PooledBrowser implements AutoCloseable {
        final Browser browser;
        final int id;

        PooledBrowser(Browser browser, int id) {
            this.browser = browser;
            this.id = id;
        }

        @Override
        public void close() {
            cache.remove(browser);
            tryClose(browser);
        }
    }

    private class ObjectFactory {
        public StringBuilder dump() {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (PooledBrowser p : snapshot()) {
                sb.appendFormat("\tBrowser[%s]", p.id);
                if (i++ % 3 == 0) {
                    sb.appendLine();
                }
            }
            return sb;
        }

        public PooledBrowser create() {
            WebBrowser browser = new WebBrowser(browserConf, BrowserType.CHROME);
            PooledBrowser pooledBrowser = new PooledBrowser(browser, idGenerator.incrementAndGet());
            cache.put(browser, pooledBrowser);
            return pooledBrowser;
        }

        public boolean validate(PooledBrowser p) {
            return !((WebBrowser) p.browser).isClosed();
        }

        public void passivate(PooledBrowser p) {
            WebBrowser browser = (WebBrowser) p.browser;
            browser.onNavigated.purge();
            browser.onNavigating.purge();
            quietly(() -> {
                if (browser.getCookieRegion() != null) {
                    browser.clearCookies(true);
                    browser.setCookieRegion(null);
                }
                if (conf.isWindowAutoBlank()) {
                    browser.navigateBlank();
                }
            });
        }

        PooledBrowser[] snapshot() {
            synchronized (cache) {
                return cache.values().toArray(new PooledBrowser[0]);
            }
        }
    }

    final AppConfig.BrowserPoolConfig conf;
    final WebBrowserConfig browserConf;
    final ObjectFactory factory;
    final ObjectPool<PooledBrowser> pool;
    final Map<Browser, PooledBrowser> cache = Collections.synchronizedMap(new IdentityHashMap<>());
    final AtomicInteger idGenerator = new AtomicInteger();
    volatile int activeCount;

    @SneakyThrows
    public BrowserPool(@NonNull AppConfig.BrowserPoolConfig config, @NonNull HttpClientCookieJar cookieJar) {
        conf = config;
        browserConf = BeanMapper.DEFAULT.map(conf, WebBrowserConfig.class);
        browserConf.setCookieJar(cookieJar);
        int poolSize = conf.getPoolSize();
        factory = new ObjectFactory();
        pool = new ObjectPool<>(poolSize, poolSize, factory::create, factory::validate, null, factory::passivate);
        pool.setName("browser");
        pool.setBorrowTimeout(conf.getTakeTimeoutSeconds() * 1000L);
        pool.setIdleTimeout(0);

        Tasks.schedulePeriod(() -> {
            log.info("\n\tChromePool: Idle={} Active={}\tIEPool: Idle={} Active={}" +
                            "\n{}\n",
                    pool.idleSize(), activeCount = activeCount(),
                    0, 0,
                    factory.dump());
        }, conf.getDumpPeriod());
    }

    @Override
    protected void dispose() {
        pool.close();
    }

    //随机访问减少cookie监控
    @SneakyThrows
    public Browser take(@NonNull BrowserType type) {
        checkNotClosed();
        if (type != BrowserType.CHROME) {
            throw new TimeoutException("Only CHROME browser pool is supported");
        }

        PooledBrowser pooledBrowser = pool.borrow();
        log.info("take {} Browser {} from pool", type, pooledBrowser.id);
        return pooledBrowser.browser;
    }

    public void release(@NonNull Browser browser) {
        checkNotClosed();

        PooledBrowser pooledBrowser = cache.get(browser);
        if (pooledBrowser == null) {
            log.warn("release {} Browser not found", browser.getType());
            return;
        }
        log.info("release {} Browser {} to pool", browser.getType(), pooledBrowser.id);
        try {
            pool.recycle(pooledBrowser);
        } catch (RuntimeException e) {
            if (Strings.startsWith(e.getMessage(), "Object has already been returned")
                    || Strings.startsWith(e.getMessage(), "Object has already in this pool")) {
                log.warn("release error, {}", e.getMessage());
                return;
            }
            TraceHandler.INSTANCE.saveExceptionTrace(Thread.currentThread(), "release", e);
        }
    }

    int activeCount() {
        return Math.max(0, pool.size() - pool.idleSize());
    }
}
