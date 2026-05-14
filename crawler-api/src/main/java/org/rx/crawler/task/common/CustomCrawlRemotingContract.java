package org.rx.crawler.task.common;

import org.rx.core.EventPublisher;
import org.rx.crawler.task.jd.JdUnionBatchRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersResult;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;

import java.util.List;

public interface CustomCrawlRemotingContract extends EventPublisher<CustomCrawlRemotingContract>, AutoCloseable {
    String EVENT_PROMOTION_RESULT = "getPromotionUrlResult";
    String EVENT_PROMOTION_ORDERS_RESULT = "getPromotionOrdersResult";
    String EVENT_TB_PROMOTION_ORDERS_RESULT = "getTbPromotionOrdersResult";
    String EVENT_TB_PROMOTION_URL_RESULT = "getTbPromotionUrlResult";

    PromotionUrlResult getPromotionUrl(PromotionUrlRequest request);

    JdUnionPromotionOrdersResult getPromotionOrders(JdUnionPromotionOrdersRequest request);

    PromotionUrlResult getTbPromotionUrl(PromotionUrlRequest request);

    TbPromotionOrdersResult getTbPromotionOrders(TbPromotionOrdersRequest request);

    PromotionUrlResult loginCheck(PromotionUrlRequest request);

    List<PromotionUrlResult> batch(JdUnionBatchRequest request);

    boolean closeProfile(String profileName);

    @Override
    default void close() {
    }
}
