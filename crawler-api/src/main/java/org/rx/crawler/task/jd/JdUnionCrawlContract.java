package org.rx.crawler.task.jd;

import org.rx.core.EventPublisher;

public interface JdUnionCrawlContract extends EventPublisher<JdUnionCrawlContract>, AutoCloseable {
    String EVENT_PROMOTION_RESULT = "getPromotionUrlResult";
    String EVENT_PROMOTION_ORDERS_RESULT = "getPromotionOrdersResult";

    JdUnionPromotionResult getPromotionUrl(JdUnionPromotionRequest request);

    JdUnionPromotionOrdersResult getPromotionOrders(JdUnionPromotionOrdersRequest request);

    JdUnionPromotionResult loginCheck(JdUnionPromotionRequest request);

    boolean closeProfile(String profileName);

    @Override
    default void close() {
    }
}
