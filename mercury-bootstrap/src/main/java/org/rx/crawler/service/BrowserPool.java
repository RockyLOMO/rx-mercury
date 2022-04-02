package org.rx.crawler.service;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.rx.bean.DateTime;
import org.rx.crawler.*;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.core.Disposable;
import org.rx.core.StringBuilder;
import org.rx.core.Strings;
import org.rx.core.Tasks;
import org.rx.exception.ExceptionHandler;
import org.rx.net.Sockets;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientMeta;
import org.rx.net.rpc.RpcServer;
import org.rx.net.rpc.RpcServerConfig;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.*;

import static org.rx.core.Extends.quietly;
import static org.rx.core.Extends.tryClose;

@Slf4j
public final class BrowserPool extends Disposable implements BrowserPoolListener {
    private class ObjectFactory extends BaseKeyedPooledObjectFactory<BrowserType, Browser> {
        private final Map<Browser, RpcServer> cache = Collections.synchronizedMap(new WeakHashMap<>());

        public ObjectFactory() {
            Tasks.schedule(() -> {
                for (Map.Entry<Browser, RpcServer> entry : cache.entrySet()) {
                    Browser browser = entry.getKey();
                    RpcServer server = entry.getValue();
                    if (asyncTopic != null && asyncTopic.isPublishing(server.getConfig().getListenPort())) {
                        continue;
                    }

                    Collection<RpcClientMeta> clients = server.getClients().values();
                    if (clients.size() == 0) {
                        try {
                            pool.returnObject(browser.getType(), browser);
                            log.warn("CORRECT browser[{}] idle status..", browser.getId());
                        } catch (IllegalStateException e) {
                            if (Strings.startsWith(e.getMessage(), "Returned object not currently part of this pool")) {
                                log.warn("CORRECT discard browser[{}] {}", browser.getId(), e.getMessage());
                                cache.remove(browser);
                                browser.close();
                                server.close();
                                continue;
                            }
                            if (!Strings.startsWith(e.getMessage(), "Object has already been returned")) {
                                log.warn("CORRECT not handle.. {}", e.getMessage());
                            }
                        }
                        continue;
                    }
                    for (RpcClientMeta client : clients) {
                        if (DateTime.now().subtract(client.getConnectedTime()).getTotalMinutes() <= config.getPool().getMaxActiveMinutes()) {
                            continue;
                        }
                        log.warn("CORRECT force close browser[{}]", browser.getId());
                        server.close(client);
                    }
                }
            }, config.getPool().getMaintenancePeriod());
        }

        public StringBuilder dump() {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (Map.Entry<Browser, RpcServer> entry : cache.entrySet()) {
                Browser browser = entry.getKey();
                sb.append("\tBrowser[%s]: ClientSize=%s", (Object) browser.getId(), entry.getValue().getClients().size());
                if (i++ % 3 == 0) {
                    sb.appendLine();
                }
            }
            return sb;
        }

        @Override
        public Browser create(BrowserType type) throws Exception {
            WebBrowser browser = new WebBrowser(config, type);
            while (true) {
                try {
                    RpcServerConfig serverConfig = new RpcServerConfig(config.getPool().getPortGenerator().increment());
                    serverConfig.setCapacity(1);
                    RpcServer server = Remoting.listen(browser, serverConfig);
                    server.onDisconnected.combine((s, e) -> release(browser));
                    browser.setId(server.getConfig().getListenPort());
                    cache.put(browser, server);
                    break;
                } catch (Exception e) {
                    log.warn("BrowserPool create error {}", e.getMessage());
                }
            }
            return browser;
        }

        @Override
        public PooledObject<Browser> wrap(Browser browser) {
            return new DefaultPooledObject<>(browser);
        }

        @Override
        public boolean validateObject(BrowserType key, PooledObject<Browser> p) {
            return !((WebBrowser) p.getObject()).isClosed();
        }

        @Override
        public void destroyObject(BrowserType key, PooledObject<Browser> p) throws Exception {
            tryClose(cache.get(p.getObject()));
            tryClose(p.getObject());
        }

        @Override
        public void passivateObject(BrowserType key, PooledObject<Browser> p) throws Exception {
            WebBrowser browser = (WebBrowser) p.getObject();
            browser.onNavigated.purge();
            browser.onNavigating.purge();
            quietly(() -> {
                if (browser.getCookieRegion() != null) {
                    browser.clearCookies(true);
                    browser.setCookieRegion(null);
                }
                if (config.isWindowAutoBlank() && browser.getErrorCount() == 0) {
                    browser.navigateBlank();
                }
            });
        }
    }

    private final GenericKeyedObjectPool<BrowserType, Browser> pool;
    private final AppConfig config;
    private volatile int activeCount;
    private final BrowserAsyncTopic asyncTopic;

    public BrowserPool(AppConfig config, BrowserAsyncTopic asyncTopic) {
        this.config = config;
        this.asyncTopic = asyncTopic;
        int poolSize = Math.max(config.getChrome().getPoolSize(), config.getIe().getPoolSize());
        GenericKeyedObjectPoolConfig<Browser> poolConfig = new GenericKeyedObjectPoolConfig<>();
        poolConfig.setLifo(false);
        poolConfig.setFairness(false);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setJmxEnabled(false);
        poolConfig.setMaxWaitMillis(config.getPool().getTakeTimeoutSeconds() * 1000);
        poolConfig.setMaxIdlePerKey(poolSize);
        poolConfig.setMaxTotalPerKey(poolSize);
        pool = new GenericKeyedObjectPool<>(new ObjectFactory(), poolConfig);
        fillPoolSize();

        Tasks.schedule(() -> {
            ObjectFactory factory = (ObjectFactory) pool.getFactory();
            log.info("\n\tChromePool: Idle={} Active={}\tIEPool: Idle={} Active={}" +
                            "\n{}\n",
                    pool.getNumIdle(BrowserType.CHROME), activeCount = pool.getNumActive(BrowserType.CHROME),
                    pool.getNumIdle(BrowserType.IE), pool.getNumActive(BrowserType.IE),
                    factory.dump());
        }, config.getPool().getDumpPeriod());

        Tasks.schedule(() -> {
            if ((float) activeCount / config.getChrome().getPoolSize() > config.getPool().getAsyncThreshold()) {
                log.warn("Pool is busy, retry next time..");
                return;
            }
//          BrowserAsyncRequest request = asyncTopic.poll();
//          Inet4Address address = Sockets.getLocalAddress();
//          int idleId = nextIdleId(BrowserType.Chrome);
//          log.info("Async publish {}:{}", address, idleId);
//          asyncTopic.publish(new BrowserAsyncResponse(request, new InetSocketAddress(address, idleId)));

            for (BrowserAsyncRequest request : asyncTopic.poll(poolSize / 2)) {
                Inet4Address address = (Inet4Address) Sockets.getLocalAddress();
                int idleId = nextIdleId(BrowserType.CHROME);
                log.info("Async publish {}:{}", address, idleId);
                asyncTopic.publish(new BrowserAsyncResponse(request, new InetSocketAddress(address, idleId)));
            }
        }, 2000);
    }

    @Override
    protected void freeObjects() {
        pool.close();
    }

    @SneakyThrows
    private synchronized void fillPoolSize() {
        for (BrowserType type : BrowserType.values()) {
            int poolSize;
            switch (type) {
                case IE:
                    poolSize = config.getIe().getPoolSize();
                    break;
                default:
                    poolSize = config.getChrome().getPoolSize();
                    break;
            }
            pool.addObjects(type, poolSize);
        }
    }

    //随机访问减少cookie监控
    @SneakyThrows
    public Browser take(@NonNull BrowserType type) {
        checkNotClosed();

        Browser browser = pool.borrowObject(type);
        log.info("take {} Browser {} from pool", type, browser.getId());
        return browser;
    }

    public void release(@NonNull Browser browser) {
        checkNotClosed();

        log.info("release {} Browser {} to pool", browser.getType(), browser.getId());
        try {
            pool.returnObject(browser.getType(), browser);
        } catch (IllegalStateException e) {
            if (Strings.startsWith(e.getMessage(), "Object has already been returned")) {
                log.warn("release error, {}", e.getMessage());
                return;
            }
            ExceptionHandler.INSTANCE.log("release", e);
        }
    }

    @Override
    public int nextIdleId(BrowserType type) {
        return take(type).getId();
    }
}
