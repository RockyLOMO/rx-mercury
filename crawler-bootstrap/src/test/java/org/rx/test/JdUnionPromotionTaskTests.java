package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.crawler.task.jd.JdUnionPromotionRequest;
import org.rx.crawler.task.jd.JdUnionPromotionResult;
import org.rx.crawler.task.jd.JdUnionPromotionTask;
import org.rx.crawler.task.common.CustomCrawlStatus;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class JdUnionPromotionTaskTests {
    @TempDir
    Path tempDir;

    @Test
    public void commonChromeProfileShouldBeDefault() {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        BrowserProfileManager manager = new BrowserProfileManager(config);

        assertEquals("common", manager.defaultProfileName());
        assertEquals(tempDir.resolve("common").toString(), manager.resolveProfileDataPath(null));
        assertEquals("jd-union-5", manager.normalizeProfileName("jd union/5"));
    }

    @Test
    public void resultWriterShouldAppendJsonLine() throws Exception {
        ResultWriter writer = new ResultWriter(new ObjectMapper());
        JdUnionPromotionResult result = new JdUnionPromotionResult();
        result.setSkuId("100059484008");
        result.setAdSiteName("5");

        Path output = tempDir.resolve("output.jsonl");
        writer.appendJsonLine(output.toString(), result);

        String content = new String(java.nio.file.Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertTrue(content.contains("100059484008"));
        assertTrue(content.endsWith(System.lineSeparator()));
    }

    @Test
    public void jdUnionConfigShouldUseOverviewAsLoginEntry() {
        AppConfig config = new AppConfig();

        assertEquals("https://union.jd.com/overview", config.getCustom().getJdUnion().getOverviewUrl());
        assertEquals("https://union.jd.com/overview", config.getCustom().getJdUnion().getLoginCheckUrl());
    }

    @Test
    public void jdUnionTaskTypeShouldBeGetPromotionUrl() {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        assertEquals("getPromotionUrl", task.taskType());
    }

    @Test
    public void overviewUrlShouldBeRecognizedAsLoggedInPage() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        Method isLoggedInUrl = JdUnionPromotionTask.class.getDeclaredMethod("isLoggedInUrl", String.class);
        isLoggedInUrl.setAccessible(true);
        assertTrue((Boolean) isLoggedInUrl.invoke(task, "https://union.jd.com/overview"));
        assertTrue((Boolean) isLoggedInUrl.invoke(task, "https://union.jd.com/proManager/index?pageNo=1"));
        assertFalse((Boolean) isLoggedInUrl.invoke(task, "https://union.jd.com/index?returnUrl=abc"));
    }

    @Test
    public void jdUnionAuthorizedIntegration() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("jd.union.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().getChrome().setProfileBasePath(
                System.getProperty("app.custom.chrome.profileBasePath", "D:/app-crawler/data/chrome"));
        config.getCustom().getJdUnion().setDefaultOutputPath(tempDir.resolve("jd-output.jsonl").toString());
        config.getCustom().getJdUnion().setForcePreflight(
                Boolean.parseBoolean(System.getProperty("app.custom.jdUnion.forcePreflight", "true")));
        config.getCustom().getJdUnion().setPreflightEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.jdUnion.preflightEnabled", "true")));

        JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);
        JdUnionPromotionRequest request = new JdUnionPromotionRequest();
        request.setSkuId(System.getProperty("jd.union.skuId", "100059484008"));
        request.setAdSiteName(System.getProperty("jd.union.adSiteName", "5"));

        JdUnionPromotionResult result = task.promotion(request);
        System.out.println("JD_UNION_RESULT=" + objectMapper.writeValueAsString(result));
        assertNotNull(result.getStatus());
        if (result.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) {
            Thread.sleep(Long.parseLong(System.getProperty("jd.union.loginWaitMillis", "300000")));
            if (Boolean.parseBoolean(System.getProperty("jd.union.retryAfterLogin", "true"))) {
                result = task.promotion(request);
                System.out.println("JD_UNION_RESULT_RETRY=" + objectMapper.writeValueAsString(result));
                assertNotNull(result.getStatus());
            }
        }
        if (result.getStatus() == CustomCrawlStatus.LOGIN_REQUIRED) {
            return;
        }
        task.closeProfile(result.getProfileName());
    }
}
