package org.rx.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.crawler.task.tb.TbPromotionOrderItem;
import org.rx.crawler.task.tb.TbPromotionOrdersRequest;
import org.rx.crawler.task.tb.TbPromotionOrdersResult;
import org.rx.crawler.task.tb.TbPromotionOrdersTask;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TbPromotionOrdersTaskTests {
    @TempDir
    Path tempDir;

    @Test
    public void tbPromotionConfigShouldUseCommonProfile() {
        AppConfig config = new AppConfig();

        assertEquals("common", config.getCustom().getTbPromotion().getProfileName());
        assertEquals("https://pub.alimama.com/portal/v2/home/plus/index.htm", config.getCustom().getTbPromotion().getHomeUrl());
        assertEquals("https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm",
                config.getCustom().getTbPromotion().getOrderUrl());
        assertEquals(180, config.getCustom().getTbPromotion().getInitialPageTimeoutSeconds());
        assertEquals(180, config.getCustom().getTbPromotion().getLoginWaitSeconds());
        assertEquals(600, config.getCustom().getTbPromotion().getStepDelayRandomMillis());
        assertEquals(80, config.getBrowser().getOperationRandomMinDelayMillis());
        assertEquals(320, config.getBrowser().getOperationRandomMaxDelayMillis());
        int nextDelay = config.getCustom().getTbPromotion().nextStepDelayMillis();
        assertTrue(nextDelay >= 1200 && nextDelay <= 1800);
    }

    @Test
    public void tbForwardLandingUrlShouldBeRecognizedAsLoggedIn() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        Method method = TbPromotionOrdersTask.class.getDeclaredMethod("isLoggedInUrl", String.class,
                org.rx.crawler.task.tb.TbPromotionConfig.class);
        method.setAccessible(true);
        assertTrue((Boolean) method.invoke(task,
                "https://pub.alimama.com/?forward=http%3A%2F%2Fpub.alimama.com%2Fportal%2Fv2%2Fhome%2Fplus%2Findex.htm",
                config.getCustom().getTbPromotion()));
    }

    @Test
    public void tbLoginJumpUrlShouldNotBeTreatedAsLoginPage() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        Method loginRequired = TbPromotionOrdersTask.class.getDeclaredMethod("isLoginRequired", String.class,
                org.rx.crawler.task.tb.TbPromotionConfig.class);
        Method loggedIn = TbPromotionOrdersTask.class.getDeclaredMethod("isLoggedInUrl", String.class,
                org.rx.crawler.task.tb.TbPromotionConfig.class);
        loginRequired.setAccessible(true);
        loggedIn.setAccessible(true);
        String url = "https://pub.alimama.com/portal/v2/home/plus/index.htm/_____tmd_____/page/login_jump?rand=abc";
        assertFalse((Boolean) loginRequired.invoke(task, url, config.getCustom().getTbPromotion()));
        assertTrue((Boolean) loggedIn.invoke(task, url, config.getCustom().getTbPromotion()));
    }

    @Test
    public void tbPromotionOrdersTimeRangeShouldRequireStartBeforeEnd() {
        TbPromotionOrdersRequest request = new TbPromotionOrdersRequest();
        request.setStartTime("2026-04-15");
        request.setEndTime("2026-05-08");
        assertTrue(request.isTimeRangeValid());

        request.setStartTime("2026-05-09");
        request.setEndTime("2026-05-08");
        assertFalse(request.isTimeRangeValid());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void tbPromotionOrderHtmlParserShouldReadRows() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        String html = "<div class='next-table'><div class='next-table-row'>"
                + "<div class='next-table-cell'>订单信息</div><div class='next-table-cell'>订单状态</div>"
                + "<div class='next-table-cell'>总提成率</div><div class='next-table-cell'>付款预估收入</div>"
                + "<div class='next-table-cell'>结算预估收入</div>"
                + "</div><div class='next-table-row'>"
                + "<div class='next-table-cell'><a href='https://item.taobao.com/item.htm?id=1'>测试商品A</a>"
                + "<div>老王旗舰店</div><div>主单号：1234567890123456</div><div>订单号：2234567890123456</div>"
                + "<div>￥100.00 2026-05-01 10:11:12</div></div>"
                + "<div class='next-table-cell'>订单付款</div>"
                + "<div class='next-table-cell'>12.50%</div>"
                + "<div class='next-table-cell'><div>预估佣金 ￥1.20</div><div>服务费 ￥0.30</div></div>"
                + "<div class='next-table-cell'><div>实际佣金 ￥1.00</div><div>补贴 ￥0.20</div></div>"
                + "</div><div class='next-table-row'>"
                + "<div class='next-table-cell'><a href='https://item.taobao.com/item.htm?id=2'>测试商品B</a>"
                + "<div>店铺名：老王专卖店</div><div>父订单编号：3234567890123456</div><div>子订单编号：4234567890123456</div>"
                + "<div>￥80.00 2026-05-02 11:12:13</div></div>"
                + "<div class='next-table-cell'>订单结算</div>"
                + "<div class='next-table-cell'>10%</div>"
                + "<div class='next-table-cell'><div>预估佣金 ￥2.00</div><div>服务费 ￥0.50</div></div>"
                + "<div class='next-table-cell'>--</div>"
                + "</div></div>";

        Method method = TbPromotionOrdersTask.class.getDeclaredMethod("parseOrderRowsFromHtml", String.class);
        method.setAccessible(true);
        List<TbPromotionOrderItem> items = (List<TbPromotionOrderItem>) method.invoke(task, html);

        assertEquals(2, items.size());
        assertEquals("测试商品A", items.get(0).getProductName());
        assertEquals("https://item.taobao.com/item.htm?id=1", items.get(0).getProductLink());
        assertEquals("老王旗舰店", items.get(0).getStoreName());
        assertEquals("1234567890123456", items.get(0).getMainOrderNo());
        assertEquals("2234567890123456", items.get(0).getOrderNo());
        assertEquals("￥100.00", items.get(0).getEstimatedBillingAmount());
        assertEquals("2026-05-01 10:11:12", items.get(0).getOrderTime());
        assertEquals("订单付款", items.get(0).getOrderStatus());
        assertEquals("12.50%", items.get(0).getCommissionRate());
        assertEquals("￥1.50", items.get(0).getEstimatedCommission());
        assertEquals("￥1.20", items.get(0).getActualCommission());
        assertEquals("￥100.00", items.get(0).getActualBillingAmount());
        assertEquals("老王专卖店", items.get(1).getStoreName());
        assertEquals("3234567890123456", items.get(1).getMainOrderNo());
        assertEquals("4234567890123456", items.get(1).getOrderNo());
        assertNull(items.get(1).getActualCommission());
        assertNull(items.get(1).getActualBillingAmount());
    }

    @Test
    public void tbSliderVerifyPageDetectionShouldMatchKeywords() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);

        Method isSlider = TbPromotionOrdersTask.class.getDeclaredMethod("isSliderVerifyPage",
                org.rx.crawler.service.Browser.class);
        isSlider.setAccessible(true);

        // 直接测 containsAny 逻辑（isSliderVerifyPage 依赖 browser，这里用反射验证关键词覆盖）
        Method containsAny = TbPromotionOrdersTask.class.getDeclaredMethod("containsAny", String.class, String[].class);
        containsAny.setAccessible(true);

        // 验证页特征文字应被识别
        assertTrue((Boolean) containsAny.invoke(task, "亲，请拖动下方滑块完成验证\n通过验证以确保正常访问",
                new String[]{"请拖动下方滑块完成验证", "拖动滑块", "拖到最右边", "按住滑块"}));
        assertTrue((Boolean) containsAny.invoke(task, "请按住滑块，拖到最右边",
                new String[]{"请拖动下方滑块完成验证", "拖动滑块", "拖到最右边", "按住滑块"}));
        // 正常订单页文字不应被误识别
        assertFalse((Boolean) containsAny.invoke(task, "订单状态 佣金比例 预估佣金 实际佣金",
                new String[]{"请拖动下方滑块完成验证", "拖动滑块", "拖到最右边", "按住滑块"}));
    }

    @Test
    public void tbPromotionOrdersIntegrationShouldSaveReadableDebugSnapshot() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty("tb.promotion.orders.integration", "false")));

        ObjectMapper objectMapper = new ObjectMapper();
        AppConfig config = new AppConfig();
        config.getCustom().setRemotingEnabled(false);
        config.getCustom().getChrome().setProfileBasePath(
                System.getProperty("app.custom.chrome.profileBasePath", "D:/app-crawler/data/chrome"));
        Path debugDir = Paths.get("target", "tb-promotion-debug").toAbsolutePath();
        config.getCustom().setDebugEnabled(true);
        config.getCustom().getTbPromotion().setDebugOutputDir(debugDir.toString());
        config.getCustom().getTbPromotion().setDefaultOutputPath(tempDir.resolve("tb-orders-output.jsonl").toString());
        config.getCustom().getTbPromotion().setForcePreflight(
                Boolean.parseBoolean(System.getProperty("app.custom.tbPromotion.forcePreflight", "true")));
        config.getCustom().getTbPromotion().setPreflightEnabled(
                Boolean.parseBoolean(System.getProperty("app.custom.tbPromotion.preflightEnabled", "true")));

        TbPromotionOrdersTask task = new TbPromotionOrdersTask(config, new BrowserProfileManager(config),
                new CrawlEntryService(new BrowserPreflightService()), new ResultWriter(objectMapper), objectMapper);
        TbPromotionOrdersRequest request = new TbPromotionOrdersRequest();
        request.setStartTime(System.getProperty("tb.promotion.orders.startTime", LocalDate.now().minusMonths(1).toString()));
        request.setEndTime(System.getProperty("tb.promotion.orders.endTime", LocalDate.now().toString()));

        TbPromotionOrdersResult result = task.getPromotionOrders(request);
        System.out.println("TB_PROMOTION_ORDERS_RESULT=" + objectMapper.writeValueAsString(result));
        assertNotNull(result.getStatus());

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
