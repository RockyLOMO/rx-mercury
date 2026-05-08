package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.crawler.task.jd.JdUnionPromotionRequest;
import org.rx.crawler.task.jd.JdUnionPromotionResult;
import org.rx.crawler.task.jd.JdUnionPromotionTask;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    public void jdUnionAuthorizedIntegration() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("jd.union.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().getChrome().setProfileBasePath(
                System.getProperty("app.custom.chrome.profileBasePath", tempDir.resolve("chrome").toString()));
        config.getCustom().getJdUnion().setDefaultOutputPath(tempDir.resolve("jd-output.jsonl").toString());
        config.getCustom().getJdUnion().setForcePreflight(
                Boolean.parseBoolean(System.getProperty("app.custom.jdUnion.forcePreflight", "true")));

        JdUnionPromotionTask task = new JdUnionPromotionTask(config, new BrowserProfileManager(config),
                new BrowserPreflightService(), new ResultWriter(objectMapper), objectMapper);
        JdUnionPromotionRequest request = new JdUnionPromotionRequest();
        request.setSkuId(System.getProperty("jd.union.skuId", "100059484008"));
        request.setAdSiteName(System.getProperty("jd.union.adSiteName", "5"));

        JdUnionPromotionResult result = task.promotion(request);
        assertNotNull(result.getStatus());
        task.closeProfile(result.getProfileName());
    }
}
