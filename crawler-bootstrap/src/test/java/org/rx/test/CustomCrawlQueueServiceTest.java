package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.CustomCrawlQueueService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.jd.JdUnionPromotionRequest;
import org.rx.crawler.task.jd.JdUnionPromotionResult;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CustomCrawlQueueServiceTest {
    @Test
    public void submitAndWaitShouldPersistAndExecuteTask() throws Exception {
        EntityDatabase entityDatabase = new EntityDatabaseImpl("jdbc:h2:mem:queue-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig appConfig = new AppConfig();
        AppConfig.CustomTaskConfig customTaskConfig = new AppConfig.CustomTaskConfig();
        customTaskConfig.setQueueMaxConcurrency(1);
        customTaskConfig.setQueueTimeoutSeconds(5);
        appConfig.setCustom(customTaskConfig);

        JdUnionPromotionTask task = mock(JdUnionPromotionTask.class);
        when(task.getPromotionUrl(any())).thenAnswer(invocation -> {
            JdUnionPromotionResult result = new JdUnionPromotionResult();
            result.setStatus(CustomCrawlStatus.SUCCESS);
            result.setMessage("");
            return result;
        });

        CustomCrawlQueueService service = new CustomCrawlQueueService(entityDatabase, objectMapper, appConfig, task);
        try {
            service.init();

            JdUnionPromotionRequest request = new JdUnionPromotionRequest();
            request.setSkuId("1001");
            request.setAdSiteName("2002");
            JdUnionPromotionResult result = service.submitAndWait("getPromotionUrl", request, JdUnionPromotionResult.class);

            assertEquals(CustomCrawlStatus.SUCCESS, result.getStatus());
            verify(task).getPromotionUrl(any());
        } finally {
            service.destroy();
            entityDatabase.close();
        }
    }

    @Test
    public void submitAndWaitShouldReturnFailedResultWhenTaskThrows() throws Exception {
        EntityDatabase entityDatabase = new EntityDatabaseImpl("jdbc:h2:mem:queue-test-fail;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", null, 4, true);
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig appConfig = new AppConfig();
        AppConfig.CustomTaskConfig customTaskConfig = new AppConfig.CustomTaskConfig();
        customTaskConfig.setQueueMaxConcurrency(1);
        customTaskConfig.setQueueTimeoutSeconds(5);
        appConfig.setCustom(customTaskConfig);

        JdUnionPromotionTask task = mock(JdUnionPromotionTask.class);
        when(task.getPromotionUrl(any())).thenThrow(new IllegalStateException("boom"));

        CustomCrawlQueueService service = new CustomCrawlQueueService(entityDatabase, objectMapper, appConfig, task);
        try {
            service.init();

            JdUnionPromotionRequest request = new JdUnionPromotionRequest();
            request.setSkuId("1001");
            request.setAdSiteName("2002");
            JdUnionPromotionResult result = service.submitAndWait("getPromotionUrl", request, JdUnionPromotionResult.class);

            assertEquals(CustomCrawlStatus.FAILED, result.getStatus());
        } finally {
            service.destroy();
            entityDatabase.close();
        }
    }
}
