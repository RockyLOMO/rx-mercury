//package org.rx.crawler.service;
//
//import com.alibaba.fastjson.TypeReference;
//import lombok.AllArgsConstructor;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.collections4.CollectionUtils;
//import org.redisson.api.RScoredSortedSet;
//import org.rx.bean.DateTime;
//import org.rx.bean.RandomList;
//import org.rx.crawler.RemoteBrowser;
//import org.rx.crawler.config.AppConfig;
//import org.rx.core.*;
//import org.rx.core.StringBuilder;
//import org.rx.crawler.dto.*;
//import org.rx.net.AuthenticEndpoint;
//import org.rx.net.PingClient;
//import org.rx.net.socks.SocksConfig;
//import org.rx.net.socks.SocksProxyServer;
//import org.rx.net.socks.upstream.Socks5Upstream;
//import org.rx.net.socks.upstream.Upstream;
//import org.rx.net.support.UnresolvedEndpoint;
//import org.rx.net.support.UpstreamSupport;
//import org.rx.redis.RedisCache;
//import org.rx.util.UrlGenerator;
//import org.springframework.stereotype.Service;
//
//import javax.annotation.PostConstruct;
//import java.io.Serializable;
//import java.util.Collection;
//import java.util.Date;
//import java.util.List;
//import java.util.NoSuchElementException;
//import java.util.concurrent.ThreadLocalRandom;
//
//import static org.rx.core.App.*;
//
//@RequiredArgsConstructor
//@Service
//@Slf4j
//public class ProxyPoolService {
//    @Data
//    @NoArgsConstructor
//    @AllArgsConstructor
//    static class CrawlStatusBean implements Serializable {
//        private Date lastCrawlTime;
//    }
//
//    private static final String CRAWL_STATUS_KEY = "proxy.crawlStatus";
//    private static final String QUEUE_KEY = "proxy.queue";
//    private static final RandomList<NetLagLevel> lagWeight = new RandomList<>();
//
//    static {
//        lagWeight.add(NetLagLevel.A, 35);
//        lagWeight.add(NetLagLevel.B, 30);
//        lagWeight.add(NetLagLevel.C, 20);
//        lagWeight.add(NetLagLevel.D, 10);
//        lagWeight.add(NetLagLevel.E, 5);
//    }
//
//    private final AppConfig config;
//    private final RedisCache<String, CrawlStatusBean> store;
//    private RScoredSortedSet<ProxyBean> queue;
//    private final RandomList<Double> queueWeight = new RandomList<>();
//    private SocksProxyServer proxyServer;
//
//    private List<CrawlPageConfig> getSourceSites() {
//        return config.getProxy().getWebsite();
//    }
//
//    private CrawlStatusBean getCrawlStatus() {
//        CrawlStatusBean crawlStatus = store.get(CRAWL_STATUS_KEY);
//        if (crawlStatus == null) {
//            crawlStatus = new CrawlStatusBean(DateTime.MIN);
//        }
//        return crawlStatus;
//    }
//
//    @PostConstruct
//    public void init() {
//        queue = store.getClient().getScoredSortedSet(QUEUE_KEY);
//        long delay = 1000 * 120;
//        Tasks.setTimeout(this::initRandomList, delay);
//
//        Tasks.scheduleDaily(this::produceProxies, config.getProxy().getProduceProxyTimes());
//        CrawlStatusBean crawlStatus = getCrawlStatus();
//        if (crawlStatus.lastCrawlTime == null || DateTime.now().subtract(crawlStatus.lastCrawlTime).getTotalHours() > 24) {
//            Tasks.setTimeout(this::produceProxies, delay);//等待spring初始化否则apiclient无法调用
//        }
//
//        SocksConfig socksConfig = new SocksConfig(1080);
//        proxyServer = new SocksProxyServer(socksConfig);
//        proxyServer.onRoute.combine((s, e) -> {
//            UnresolvedEndpoint dstEp = e.getDestinationEndpoint();
//            try {
//                ProxyBean proxy = nextProxy();
//                log.info("Upstream connect {}", toJsonString(proxy));
//                Socks5Upstream upstream = new Socks5Upstream(dstEp, socksConfig, () -> new UpstreamSupport(AuthenticEndpoint.valueOf(proxy.getEndpoint()), null));
//                Cache.getInstance(Cache.MEMORY_CACHE).put(upstream, proxy);
//                e.setValue(upstream);
//            } catch (NoSuchElementException ex) {
//                log.warn("no proxy {}", ex.getMessage());
//                e.setValue(new Upstream(dstEp));
//            }
//        });
//        proxyServer.onReconnecting.combine((s, e) -> {
//            ProxyBean proxy = Cache.<Upstream, ProxyBean>getInstance(Cache.MEMORY_CACHE).get(e.getValue());
//            if (proxy != null) {
//                Double score = queue.addScore(proxy, 100);
//                if (score > config.getProxy().getMaxLagMills()) {
//                    log.info("Upstream remove dead proxy {}[{}]", toJsonString(proxy), score);
//                    queue.removeAsync(proxy).whenComplete((r, ex) -> {
//                        if (!r) {
//                            log.error("queue remove", ex);
//                            return;
//                        }
//                        if (queue.size() < config.getProxy().getQueueMinCountToRaiseProduce()) {
//                            produceProxies();
//                        }
//                    });
//                }
//            }
//            try {
//                log.info("Upstream preReconnect {}", toJsonString(proxy = nextProxy()));
//                ProxyBean finalProxy = proxy;
//                e.setValue(new Socks5Upstream(e.getValue().getDestination(), socksConfig, () -> new UpstreamSupport(AuthenticEndpoint.valueOf(finalProxy.getEndpoint()), null)));
//            } catch (NoSuchElementException ex) {
//                log.warn("no proxy {}", ex.getMessage());
//            }
//        });
//    }
//
//    private void initRandomList() {
//        List<String> socks5 = config.getProxy().getSocks5();
//        for (int i = 0; i < socks5.size(); i++) {
//            add(1, new ProxyBean(ProxyType.Socks5, CountryType.China, socks5.get(i)));
//        }
//
//        queue.valueRangeAsync(0, config.getProxy().getQueueSyncCount()).whenComplete((r, e) -> {
//            if (CollectionUtils.isEmpty(r)) {
//                return;
//            }
//            for (ProxyBean proxy : r) {
//                queueWeight.add(proxy.getPingValue(), computeWeight(proxy.getPingValue()));
//            }
//        });
//    }
//
//    public void produceProxies() {
//        Tasks.run(() -> {
//            for (CrawlPageConfig crawlPageConfig : getSourceSites()) {
//                RemoteBrowser.invokeAsync(crawlPageConfig.getUrl(), (crawler, urlExpression) -> {
//                    UrlGenerator generator = new UrlGenerator(urlExpression);
//                    for (String url : generator) {
//                        quietly(() -> {
//                            crawler.navigateUrl(url, crawlPageConfig.getLocatorSelector(), crawlPageConfig.getTimeoutSeconds());
//                            String jsonCallback = crawler.executeConfigureScript(crawlPageConfig.getScriptName());
//                            log.info("produceProxy {} -> {}", url, jsonCallback);
//                            List<ProxyBean> proxies = fromJson(jsonCallback, new TypeReference<List<ProxyBean>>() {
//                            }.getType());
//                            Tasks.run(() -> {
//                                PingClient client = new PingClient();
//                                for (ProxyBean proxy : proxies) {
//                                    PingClient.Result r = client.ping(proxy.getEndpoint());
//                                    if (r.getAvg() > config.getProxy().getMaxLagMills()) {
//                                        return;
//                                    }
//
//                                    proxy.setPingValue(r.getAvg());
//                                    proxy.setUpdateTime(DateTime.now());
//                                    add(r.getAvg(), proxy);
//                                }
//                            }, url, RunFlag.SINGLE);
//                        });
//                    }
//                });
//            }
//            store.put(CRAWL_STATUS_KEY, new CrawlStatusBean(DateTime.now()));
//        }, "produceProxy", RunFlag.SINGLE);
//    }
//
//    private void add(double scored, ProxyBean proxy) {
//        queue.addAsync(scored, proxy).whenComplete((r, e) -> {
//            if (!r) {
//                return;
//            }
//            queueWeight.add(scored, computeWeight(proxy.getPingValue()));
//        });
//    }
//
//    private int computeWeight(double pingValue) {
//        NetLagLevel[] values = NetLagLevel.values();
//        NetLagLevel lagLevel = NQuery.of(values).firstOrDefault(p -> p.getThreshold().has((float) pingValue));
//        if (lagLevel == null) {
//            lagLevel = NetLagLevel.E;
//        }
//        return values.length - lagLevel.ordinal();
//    }
//
////    public PagedResponse<ProxyBean> getProxies(PagingRequest request) {
////        Collection<ProxyBean> proxies = queue.valueRange((request.getPageIndex() - 1) * request.getPageSize(), request.getPageSize());
////        PagedResponse<ProxyBean> response = new PagedResponse<>();
////        response.setData(NQuery.of(proxies).toList());
////        response.setTotalCount(queue.size());
////        response.setPageIndex(request.getPageIndex());
////        return response;
////    }
//
//    //load balancing
//    public ProxyBean nextProxy() {
//        StringBuilder msg = new StringBuilder();
//        try {
//            double score = queueWeight.next();
//            Collection<ProxyBean> proxies = queue.valueRange(score, true, score, true, 0, 4);
//            msg.append("nextProxy scored=%s & proxies=%s", score, toJsonString(proxies));
//            if (CollectionUtils.isEmpty(proxies)) {
//                NetLagLevel lagLevel = lagWeight.next();
//                proxies = queue.valueRange(lagLevel.getThreshold().start, true, lagLevel.getThreshold().end, true, 0, 4);
//                msg.append("\n\tnextProxy lagLevel=%s & proxies=%s", lagLevel, toJsonString(proxies));
//                if (CollectionUtils.isEmpty(proxies)) {
//                    ProxyBean first = queue.first();
//                    msg.append("\n\tnextProxy first=%s", toJsonString(first));
//                    return first;
//                }
//            }
//            int i = ThreadLocalRandom.current().nextInt(0, proxies.size());
//            msg.append("\trnd=%s", i);
//            return NQuery.of(proxies).skip(i).first();
//        } catch (NoSuchElementException e) {
//            produceProxies();
//            throw e;
//        } finally {
//            log.info(msg.toString());
//        }
//    }
//}
