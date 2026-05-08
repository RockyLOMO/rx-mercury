package org.rx.crawler.task.jd;

import org.rx.core.EventPublisher;

public interface JdUnionCrawlContract extends EventPublisher<JdUnionCrawlContract>, AutoCloseable {
    String EVENT_PROMOTION_RESULT = "getPromotionUrlResult";

    JdUnionPromotionResult getPromotionUrl(JdUnionPromotionRequest request);

    JdUnionPromotionResult promotion(JdUnionPromotionRequest request);

    JdUnionPromotionResult loginCheck(JdUnionPromotionRequest request);

    boolean closeProfile(String profileName);

    @Override
    default void close() {
    }
}
