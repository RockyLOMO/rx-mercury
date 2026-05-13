package org.rx.crawler.task.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.jd.JdUnionBatchRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersResult;
import org.rx.crawler.task.jd.JdUnionPromotionRequest;
import org.rx.crawler.task.jd.JdUnionPromotionResult;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;
import org.rx.crawler.task.tb.TbPromotionOrdersTask;
import org.rx.crawler.task.tb.TbPromotionUrlRequest;
import org.rx.crawler.task.tb.TbPromotionUrlResult;
import org.rx.crawler.task.tb.TbPromotionUrlTask;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RemotingEventArgs;
import org.rx.net.transport.TcpServer;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;

import static org.rx.core.Extends.tryClose;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomCrawlRemotingService implements CustomCrawlRemotingContract {
    private final AppConfig appConfig;
    private final JdUnionPromotionTask jdUnionPromotionTask;
    private final TbPromotionOrdersTask tbPromotionOrdersTask;
    private final TbPromotionUrlTask tbPromotionUrlTask;
    private TcpServer remotingServer;

    @PostConstruct
    public void init() {
        if (!appConfig.getCustom().isRemotingEnabled() || appConfig.getCustom().getRemotingListenPort() <= 0) {
            return;
        }
        try {
            remotingServer = Remoting.register(this, appConfig.getCustom().getRemotingListenPort(), false);
            log.info("Custom crawl remoting listen on {}", appConfig.getCustom().getRemotingListenPort());
        } catch (Exception e) {
            log.warn("Custom crawl remoting register fail, port={}, error={}",
                    appConfig.getCustom().getRemotingListenPort(), e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        tryClose(remotingServer);
    }

    @Override
    public JdUnionPromotionResult getPromotionUrl(JdUnionPromotionRequest request) {
        JdUnionPromotionResult result = jdUnionPromotionTask.getPromotionUrl(request);
        publishDirect(EVENT_PROMOTION_RESULT, result);
        return result;
    }

    @Override
    public JdUnionPromotionOrdersResult getPromotionOrders(JdUnionPromotionOrdersRequest request) {
        JdUnionPromotionOrdersResult result = jdUnionPromotionTask.getPromotionOrders(request);
        publishDirect(EVENT_PROMOTION_ORDERS_RESULT, result);
        return result;
    }

    @Override
    public TbPromotionOrdersResult getTbPromotionOrders(TbPromotionOrdersRequest request) {
        TbPromotionOrdersResult result = tbPromotionOrdersTask.getPromotionOrders(request);
        publishDirect(EVENT_TB_PROMOTION_ORDERS_RESULT, result);
        return result;
    }

    @Override
    public TbPromotionUrlResult getTbPromotionUrl(TbPromotionUrlRequest request) {
        TbPromotionUrlResult result = tbPromotionUrlTask.getPromotionUrl(request);
        publishDirect(EVENT_TB_PROMOTION_URL_RESULT, result);
        return result;
    }

    @Override
    public JdUnionPromotionResult loginCheck(JdUnionPromotionRequest request) {
        JdUnionPromotionResult result = jdUnionPromotionTask.loginCheck(request);
        publishDirect(EVENT_PROMOTION_RESULT, result);
        return result;
    }

    @Override
    public List<JdUnionPromotionResult> batch(JdUnionBatchRequest request) {
        return jdUnionPromotionTask.batch(request);
    }

    @Override
    public boolean closeProfile(String profileName) {
        return jdUnionPromotionTask.closeProfile(profileName)
                || tbPromotionOrdersTask.closeProfile(profileName)
                || tbPromotionUrlTask.closeProfile(profileName);
    }

    private void publishDirect(String eventName, Object result) {
        try {
            publishEvent(eventName, RemotingEventArgs.direct(result));
        } catch (Exception e) {
            log.debug("Ignore direct event publish outside remoting context: {}", e.getMessage());
        }
    }
}
