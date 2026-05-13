package org.rx.crawler.task.common;

import org.rx.core.EventPublisher;
import org.rx.crawler.task.jd.JdUnionBatchRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersRequest;
import org.rx.crawler.task.jd.JdUnionPromotionOrdersResult;
import org.rx.crawler.task.jd.JdUnionPromotionRequest;
import org.rx.crawler.task.jd.JdUnionPromotionResult;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;
import org.rx.crawler.task.tb.TbPromotionUrlRequest;
import org.rx.crawler.task.tb.TbPromotionUrlResult;

import java.util.List;

public interface CustomCrawlRemotingContract extends EventPublisher<CustomCrawlRemotingContract>, AutoCloseable {
    String EVENT_PROMOTION_RESULT = "getPromotionUrlResult";
    String EVENT_PROMOTION_ORDERS_RESULT = "getPromotionOrdersResult";
    String EVENT_TB_PROMOTION_ORDERS_RESULT = "getTbPromotionOrdersResult";
    String EVENT_TB_PROMOTION_URL_RESULT = "getTbPromotionUrlResult";

    JdUnionPromotionResult getPromotionUrl(JdUnionPromotionRequest request);

    JdUnionPromotionOrdersResult getPromotionOrders(JdUnionPromotionOrdersRequest request);

    TbPromotionOrdersResult getTbPromotionOrders(TbPromotionOrdersRequest request);

    TbPromotionUrlResult getTbPromotionUrl(TbPromotionUrlRequest request);

    JdUnionPromotionResult loginCheck(JdUnionPromotionRequest request);

    List<JdUnionPromotionResult> batch(JdUnionBatchRequest request);

    boolean closeProfile(String profileName);

    @Override
    default void close() {
    }
}
