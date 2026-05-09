package org.rx.crawler.service;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.exception.TraceHandler;
import org.rx.net.http.HttpClientCookieJar;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.transport.TcpClient;
import org.rx.net.transport.TcpServer;
import org.rx.net.transport.TcpServerConfig;
import org.rx.util.BeanMapper;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.quietly;
import static org.rx.core.Extends.tryClose;

@Slf4j
public final class BrowserPool extends Disposable {
    final class PooledBrowser implements AutoCloseable {
        final Browser browser;
        final TcpServer server;
        final int id;

        PooledBrowser(Browser browser, TcpServer server) {
            this.browser = browser;
            this.server = server;
            id = server.getConfig().getListenPort();
        }

        @Override
        public void close() {
            cache.remove(browser);
            tryClose(server);
            tryClose(browser);
        }
    }

    private class ObjectFactory {
        static final String CONNECT_TIME = "connectTime";

        public ObjectFactory() {
            Tasks.schedulePeriod(() -> {
                for (PooledBrowser pooledBrowser : snapshot()) {
                    TcpServer server = pooledBrowser.server;
                    Integer id = pooledBrowser.id;

                    Collection<TcpClient> clients = server.getClients().values();
                    if (clients.size() == 0) {
                        try {
                            pool.recycle(pooledBrowser);
                            log.warn("CORRECT browser[{}] idle status..", id);
                        } catch (IllegalStateException e) {
                            if (!Strings.startsWith(e.getMessage(), "Object has already been returned")) {
                                log.warn("CORRECT not handle.. {}", e.getMessage());
                            }
                        } catch (Exception e) {
                            if (!Strings.startsWith(e.getMessage(), "Object has already in this pool")) {
                                log.warn("CORRECT not handle.. {}", e.getMessage());
                            }
                        }
                        continue;
                    }
                    for (TcpClient client : clients) {
                        DateTime connTime = client.attr(CONNECT_TIME);
                        if (connTime == null || DateTime.now().subtract(connTime).toMinutes() <= conf.getMaxActiveMinutes()) {
                            continue;
                        }
                        log.warn("CORRECT force close browser[{}]", id);
                        client.close();
                    }
                }
            }, conf.getMaintenancePeriod());
        }

        public StringBuilder dump() {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (PooledBrowser p : snapshot()) {
                sb.appendFormat("\tBrowser[%s]: ClientSize=%s", p.id, p.server.getClients().size());
                if (i++ % 3 == 0) {
                    sb.appendLine();
                }
            }
            return sb;
        }

        public PooledBrowser create() {
            WebBrowser browser = new WebBrowser(browserConf, BrowserType.CHROME);
            while (true) {
                try {
                    RpcServerConfig serverConfig = new RpcServerConfig(new TcpServerConfig(conf.getPortGenerator().increment()));
                    serverConfig.getTcpConfig().setCapacity(1);
                    TcpServer server = Remoting.register(browser, serverConfig);
                    server.onDisconnected.add((s, e) -> quietly(() -> release(browser)));
                    server.onConnected.add((s, e) -> e.getClient().attr(CONNECT_TIME, DateTime.now()));
                    PooledBrowser pooledBrowser = new PooledBrowser(browser, server);
                    cache.put(browser, pooledBrowser);
                    return pooledBrowser;
                } catch (Exception e) {
                    log.warn("BrowserPool create error {}", e.getMessage());
                }
            }
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
