package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.crawler.task.jd.JdUnionProductInfoDto;
import org.rx.crawler.task.tb.TbPromotionConfig;
import org.rx.crawler.task.tb.TbPromotionUrlRequest;
import org.rx.crawler.task.tb.TbPromotionUrlResult;
import org.rx.crawler.task.tb.TbPromotionUrlTask;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TbPromotionUrlTaskTests {
    @TempDir
    Path tempDir;

    @Test
    public void tbPromotionUrlTaskTypeShouldBeGetTbPromotionUrl() {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        assertEquals("getTbPromotionUrl", task.taskType());
    }

    @Test
    public void tbPromotionGoodsUrlShouldBeRecognizedAsLoggedIn() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        Method method = TbPromotionUrlTask.class.getDeclaredMethod("isLoggedInUrl", String.class,
                TbPromotionConfig.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(task,
                "https://pub.alimama.com/portal/v2/pages/promo/goods/index.htm",
                config.getCustom().getTbPromotion()));
    }

    @Test
    public void tbPromotionUrlRequestShouldNormalizeDefaults() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        config.getCustom().getTbPromotion().setDefaultMediaName("laowang");
        config.getCustom().getTbPromotion().setDefaultOutputPath(tempDir.resolve("tb-output.jsonl").toString());
        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        TbPromotionUrlRequest request = new TbPromotionUrlRequest();
        request.setProductInfo("西麦纯燕麦片3kg");
        request.setAdSiteName("5");

        Method method = TbPromotionUrlTask.class.getDeclaredMethod("normalizeRequest",
                TbPromotionUrlRequest.class, TbPromotionConfig.class);
        method.setAccessible(true);
        TbPromotionUrlRequest normalized = (TbPromotionUrlRequest) method.invoke(task, request,
                config.getCustom().getTbPromotion());

        assertEquals("common", normalized.getProfileName());
        assertEquals("laowang", normalized.getMediaName());
        assertEquals(tempDir.resolve("tb-output.jsonl").toString(), normalized.getOutputPath());
    }

    @Test
    public void tbPromotionProductInfoShouldPreferTitleAnchorLink() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("imageUrl", "https://img.alicdn.com/demo.jpg");
        raw.put("productName", "西麦纯燕麦片3kg高蛋白质0添加蔗糖即食谷物速食懒人代餐冲饮早餐");
        raw.put("productLink", "https://pub.alimama.com/portal/v2/pages/promo/goods/detail.htm?itemId=ygoRdzTAC2R2dpPWOFrD9tvtA-ZdDBpWPUGDgXBd5JfN");
        raw.put("commissionRate", "1.80%");
        raw.put("finalPrice", "44.90");
        raw.put("storeName", "seamild西麦旗舰店");

        Browser browser = mock(Browser.class);
        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        when(browser.<Map<String, Object>>executeScript(scriptCaptor.capture())).thenReturn(raw);

        Method method = TbPromotionUrlTask.class.getDeclaredMethod("readProductInfo", Browser.class);
        method.setAccessible(true);
        JdUnionProductInfoDto dto = (JdUnionProductInfoDto) method.invoke(task, browser);

        assertEquals(raw.get("productLink"), dto.getProductLink());
        String script = scriptCaptor.getValue();
        assertTrue(script.contains("a[href][data-spm-click*=\\\"d_good_select_list_title_click\\\"]"));
        assertTrue(script.contains(".union-good-card-block-title-wrap"));
    }

    @Test
    public void tbPromotionUrlIntegrationShouldSaveReadableDebugSnapshot() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("tb.promotion.url.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().getChrome().setProfileBasePath(
                System.getProperty("app.custom.chrome.profileBasePath", "D:/app-crawler/data/chrome"));
        Path debugDir = Paths.get("target", "tb-promotion-url-debug").toAbsolutePath();
        config.getCustom().setDebugEnabled(true);
        config.getCustom().getTbPromotion().setDebugOutputDir(debugDir.toString());
        config.getCustom().getTbPromotion().setDefaultOutputPath(tempDir.resolve("tb-url-output.jsonl").toString());
        config.getCustom().getTbPromotion().setForcePreflight(
                Boolean.parseBoolean(System.getProperty("app.custom.tbPromotion.forcePreflight", "true")));
        config.getCustom().getTbPromotion().setPreflightEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.tbPromotion.preflightEnabled", "true")));

        TbPromotionUrlTask task = new TbPromotionUrlTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);
        TbPromotionUrlRequest request = new TbPromotionUrlRequest();
        request.setProductInfo(System.getProperty("tb.promotion.url.productInfo",
                "西麦纯燕麦片3kg高蛋白质0添加蔗糖即食谷物速食懒人代餐冲饮早餐"));
        request.setAdSiteName(System.getProperty("tb.promotion.url.adSiteName", "5"));

        TbPromotionUrlResult result = task.getPromotionUrl(request);
        System.out.println("TB_PROMOTION_URL_RESULT=" + objectMapper.writeValueAsString(result));
        if (result.getProductInfo() != null) {
            System.out.println("TB_PROMOTION_URL_SUMMARY status=" + result.getStatus()
                    + ", promotionUrl=" + result.getPromotionUrl()
                    + ", productName=" + result.getProductInfo().getProductName()
                    + ", productLink=" + result.getProductInfo().getProductLink()
                    + ", commissionRate=" + result.getProductInfo().getCommissionRate()
                    + ", finalPrice=" + result.getProductInfo().getFinalPrice()
                    + ", storeName=" + result.getProductInfo().getStoreName());
        }
        assertNotNull(result.getStatus());
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            assertNotNull(result.getProductInfo());
            assertTrue(result.getProductInfo().getProductLink() != null
                    && !result.getProductInfo().getProductLink().trim().isEmpty());
        }

        Optional<Path> snapshot = Files.walk(debugDir)
                .filter(path -> path.getFileName().toString().endsWith(".html"))
                .sorted(Comparator.comparing(Path::toString))
                .findFirst();
        assertTrue(snapshot.isPresent());
        String html = new String(Files.readAllBytes(snapshot.get()), StandardCharsets.UTF_8);
        assertTrue(html.contains("<html") || html.contains("<body") || html.contains("阿里妈妈") || html.contains("淘宝"));
        if (result.getStatus() != CustomCrawlStatus.LOGIN_REQUIRED) {
            task.closeProfile(request.getProfileName());
        }
    }
}
