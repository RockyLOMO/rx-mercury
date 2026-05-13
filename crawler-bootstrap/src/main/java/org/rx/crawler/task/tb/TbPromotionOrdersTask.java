package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.CrawlEntryResult;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomCrawlTask;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.exception.InvalidException;
import org.rx.util.BeanMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.rx.core.Extends.tryClose;

@Slf4j
@Service
@RequiredArgsConstructor
public class TbPromotionOrdersTask implements CustomCrawlTask<TbPromotionOrdersRequest, TbPromotionOrdersResult> {
    private static final String TASK_TYPE = "getTbPromotionOrders";
    private static final DateTimeFormatter DEBUG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern MONTH_PATTERN = Pattern.compile("(\\d{4})年(\\d{1,2})月");
    private static final Pattern MONEY_PATTERN = Pattern.compile("[￥¥]\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern ANY_NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern DATE_TIME_PATTERN = Pattern
            .compile("\\d{4}-\\d{2}-\\d{2}(?:\\s+\\d{2}:\\d{2}:\\d{2})?");
    private static final Pattern LONG_NO_PATTERN = Pattern.compile("\\b[A-Za-z0-9_-]{10,}\\b");
    private static final String QUICK_ENTER_TEXT = "快速进入";
    private static final String QUICK_LOGIN_TEXT = "快速登录";
    private static final String JS_VISIBLE_STRICT = "function visible(el){for(var p=el;p&&p.nodeType===1;p=p.parentElement){var st=getComputedStyle(p),c=((p.className||'')+'').toLowerCase();if(st.display==='none'||st.visibility==='hidden'||c.indexOf('hidden')>=0){return false;}}var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}";

    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;
    private final CrawlEntryService entryService;
    private final ResultWriter resultWriter;
    private final ObjectMapper objectMapper;
    private final ThreadLocal<Long> taskDeadlineHolder = new ThreadLocal<Long>();

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public TbPromotionOrdersResult execute(TbPromotionOrdersRequest request) {
        return getPromotionOrders(request);
    }

    public TbPromotionOrdersResult getPromotionOrders(TbPromotionOrdersRequest request) {
        return executeInternal(request);
    }

    public boolean closeProfile(String profileName) {
        return profileManager.closeSession(profileName);
    }

    private TbPromotionOrdersResult executeInternal(TbPromotionOrdersRequest rawRequest) {
        TbPromotionConfig tbConfig = appConfig.getCustom().getTbPromotion();
        TbPromotionOrdersRequest request = normalizeRequest(rawRequest, tbConfig);
        TbPromotionOrdersResult result = createResult(request);
        DebugRecorder debug = new DebugRecorder(request, tbConfig);
        if (debug.enabled()) {
            result.getDiagnostics().put("debugEnabled", true);
            result.getDiagnostics().put("debugOutputDir", debug.getTaskDir().toString());
        }

        BrowserProfileManager.ProfileLease lease = null;
        taskDeadlineHolder.set(System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(Math.max(1, appConfig.getCustom().getMaxTaskMinutes())));
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(request, tbConfig));
            Browser browser = lease.getBrowser();

            CrawlEntryResult entry = entryService.enter(browser, lease, createEntryOptions(request, tbConfig));
            applyEntryResult(result, entry);
            debug.snapshot(browser, "01-entry");
            if (!entry.isPassed()) {
                debug.snapshotText("01-entry-failed", result.getMessage());
                return result;
            }

            runOrdersFlow(browser, request, tbConfig, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("TB promotion orders fail, startTime={}, endTime={}, profile={}, error={}",
                    request.getStartTime(), request.getEndTime(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            resultWriter.appendJsonLine(request.getOutputPath(), result);
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private CrawlEntryOptions createEntryOptions(TbPromotionOrdersRequest request, TbPromotionConfig config) {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setProfileName(request.getProfileName());
        options.setPreflightEnabled(config.isPreflightEnabled());
        options.setPreflightUrl(config.getPreflightUrl());
        options.setPreflightStrict(config.isPreflightStrict());
        options.setPreflightCacheMinutes(config.getPreflightCacheMinutes());
        options.setForcePreflight(
                request.getForcePreflight() == null ? config.isForcePreflight() : request.getForcePreflight());
        options.setInitialUrl(config.getHomeUrl());
        options.setLoginUrlPrefix(config.getLoginUrlPrefix());
        options.setInitialPageTimeoutSeconds(config.getInitialPageTimeoutSeconds());
        options.setLoginWaitSeconds(config.getLoginWaitSeconds());
        options.setStepDelayMillis(config.getStepDelayMillis());
        options.setStepDelayRandomMillis(config.getStepDelayRandomMillis());
        options.setKeepBrowserOpenOnLoginRequired(request.getKeepBrowserOpenOnLoginRequired() == null
                ? config.isKeepBrowserOpenOnLoginRequired()
                : request.getKeepBrowserOpenOnLoginRequired());
        options.setKeepBrowserOpenSecondsOnLoginRequired(config.getKeepBrowserOpenSecondsOnLoginRequired());
        options.setLoginRequiredUrlMatcher(url -> isLoginRequired(url, config));
        options.setLoggedInUrlMatcher(url -> isLoggedInUrl(url, config));
        options.setLoggedInBodyMatcher(body -> !containsAny(body, "扫码登录", "密码登录", "验证码")
                && containsAny(body, "阿里妈妈", "淘宝联盟", "效果报表", "订单", QUICK_ENTER_TEXT, QUICK_LOGIN_TEXT));
        return options;
    }

    private void runOrdersFlow(Browser browser, TbPromotionOrdersRequest request, TbPromotionConfig config,
            TbPromotionOrdersResult result, DebugRecorder debug) throws TimeoutException {
        browser.maximize();
        if (completeForwardLanding(browser, config)) {
            debug.snapshot(browser, "02-forward-landing-entered");
        }
        if (isForwardLandingIncomplete(browser)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion forward landing quick-enter page not completed");
            result.getDiagnostics().put("currentUrl", browser.getCurrentUrl());
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "02-forward-landing-incomplete");
            return;
        }
        browser.navigateUrl(config.getOrderUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        Extends.sleep(config.nextStepDelayMillis());
        // 导航后检测并处理滑块验证
        if (!checkAndHandleSliderVerify(browser, config, result, debug, "02-order-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED,
                    "TB promotion slider verify not cleared before order page ready");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "02-order-slider-not-cleared");
            return;
        }
        result.setCurrentUrl(browser.getCurrentUrl());
        debug.snapshot(browser, "02-order-page-loaded");
        if (isLoginRequired(result.getCurrentUrl(), config)) {
            fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                    "TB promotion login expired before entering order page. Finish login in the opened common Chrome profile, then retry.");
            result.setLoginRequired(true);
            debug.snapshot(browser, "02-order-login-required");
            return;
        }
        if (!waitOrderPageReady(browser, config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion order page not ready");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "02-order-page-not-ready");
            return;
        }
        debug.snapshot(browser, "02-order-page-ready");

        LocalDate startDate = LocalDate.parse(request.getStartTime(), DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(request.getEndTime(), DATE_FORMATTER);
        if (!selectPaymentDateRange(browser, startDate, endDate, config, debug)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion order date range picker not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "03-date-range-missing");
            return;
        }
        debug.snapshot(browser, "03-date-range-selected");

        if (!nativeClickOrderSearchButton(browser)) {
            Extends.sleep(config.nextStepDelayMillis());
            waitOrderRowsSettled(browser, config);
            if (!isOrderListLoaded(browser)) {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion order search button not found");
                result.getDiagnostics().put("body", bodySnippet(browser));
                debug.snapshot(browser, "04-search-button-missing");
                return;
            }
            result.getDiagnostics().put("searchButtonMissingSkipped", true);
            debug.snapshot(browser, "04-search-button-skipped");
        } else {
            Extends.sleep(config.nextStepDelayMillis() * 2L);
        }
        // 搜索后检测滑块验证
        if (!checkAndHandleSliderVerify(browser, config, result, debug, "04-search-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared after search");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "04-search-slider-not-cleared");
            return;
        }
        waitOrderRowsSettled(browser, config);
        debug.snapshot(browser, "04-search-clicked");

        LinkedHashMap<String, TbPromotionOrderItem> orders = new LinkedHashMap<String, TbPromotionOrderItem>();
        int pageNo = 1;
        while (pageNo <= 100) {
            ensureTaskDeadline("getTbPromotionOrders.pageLoop");
            List<TbPromotionOrderItem> pageOrders = readOrderRows(browser);
            result.getDiagnostics().put("lastPageNo", pageNo);
            result.getDiagnostics().put("lastPageRows", pageOrders.size());
            for (TbPromotionOrderItem item : pageOrders) {
                orders.put(rowKey(item), item);
            }
            debug.snapshot(browser, String.format("05-page-%03d-collected", pageNo));

            scrollOrderPageBottom(browser, config);
            if (!hasNextOrderPage(browser)) {
                break;
            }
            if (!nativeClickNextOrderPage(browser)) {
                result.getDiagnostics().put("nextPageClickFailedAt", pageNo);
                break;
            }
            Extends.sleep(config.nextStepDelayMillis() * 2L);
            // 翻页后检测滑块验证
            if (!checkAndHandleSliderVerify(browser, config, result, debug,
                    String.format("05-page-%03d-slider", pageNo + 1))) {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared during page turn");
                result.getDiagnostics().put("body", bodySnippet(browser));
                return;
            }
            waitOrderRowsSettled(browser, config);
            pageNo++;
        }

        result.setOrders(new ArrayList<TbPromotionOrderItem>(orders.values()));
        result.getDiagnostics().put("pages", pageNo);
        result.getDiagnostics().put("orderCount", result.getOrders().size());
        result.setStatus(CustomCrawlStatus.SUCCESS);
    }

    /**
     * 检测页面是否出现阿里妈妈滑块验证，若出现则模拟人工缓慢拖拽滑块到最右侧完成验证。
     * 每次遇到验证最多重试 3 次，每次失败后等待一段时间再重试。
     * 验证成功或无验证时正常返回；自动验证失败后等待人工接管，仍未通过时仅记录诊断信息。
     */
    private boolean checkAndHandleSliderVerify(Browser browser, TbPromotionConfig config,
            TbPromotionOrdersResult result, DebugRecorder debug, String stepTag) throws TimeoutException {
        for (int attempt = 0; attempt < 3; attempt++) {
            ensureTaskDeadline("checkAndHandleSliderVerify." + stepTag);
            if (!isSliderVerifyPage(browser)) {
                return true;
            }
            // 发现验证页，记录快照
            log.info("TB promotion slider verify detected, step={}, attempt={}", stepTag, attempt + 1);
            debug.snapshot(browser, stepTag + "-before-slide-" + (attempt + 1));
            result.getDiagnostics().put("sliderVerifyAt", stepTag);

            boolean slid = simulateSlideDrag(browser, config, debug, stepTag, attempt + 1);
            Extends.sleep(Math.max(1500, config.nextStepDelayMillis() * 2L));
            debug.snapshot(browser, stepTag + "-after-slide-" + (attempt + 1));

            if (slid && !isSliderVerifyPage(browser)) {
                log.info("TB promotion slider verify passed, step={}, attempt={}", stepTag, attempt + 1);
                result.getDiagnostics().put("sliderVerifyPassed", true);
                return true;
            }
            log.warn("TB promotion slider verify not resolved, step={}, attempt={}, slid={}", stepTag, attempt + 1,
                    slid);

            // 检测"验证失败，点击框体重试"文案，先点击框体触发重置
            clickRetryContainerIfPresent(browser, debug, stepTag, attempt + 1);

            // 等待一段时间再重试
            Extends.sleep(Math.max(2000, config.nextStepDelayMillis() * 3L));
        }
        log.warn("TB promotion slider verify failed after 3 attempts, step={}", stepTag);
        debug.snapshot(browser, stepTag + "-manual-wait");
        if (waitSliderVerifyCleared(browser, config, stepTag)) {
            log.info("TB promotion slider verify cleared by manual takeover, step={}", stepTag);
            result.getDiagnostics().put("sliderVerifyManualPassed", true);
            debug.snapshot(browser, stepTag + "-manual-cleared");
            return true;
        }
        result.getDiagnostics().put("sliderVerifyFailed", stepTag);
        return false;
    }

    private boolean waitSliderVerifyCleared(Browser browser, TbPromotionConfig config, String stepTag)
            throws TimeoutException {
        int waitSeconds = Math.max(10, config.getLoginWaitSeconds());
        extendTaskDeadline(waitSeconds + Math.max(10, config.getPageTimeoutSeconds()));
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(waitSeconds);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("waitSliderVerifyCleared." + stepTag);
            if (!isSliderVerifyPage(browser)) {
                return true;
            }
            Extends.sleep(Math.max(1000L, config.nextStepDelayMillis()));
        }
        return false;
    }

    private void extendTaskDeadline(int seconds) {
        Long current = taskDeadlineHolder.get();
        if (current == null) {
            return;
        }
        long minDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, seconds));
        if (current < minDeadline) {
            taskDeadlineHolder.set(minDeadline);
        }
    }

    /**
     * 检测当前页面是否为阿里妈妈滑块验证页（通过页面文字特征或 URL 判断）。
     */
    private boolean isSliderVerifyPage(Browser browser) {
        try {
            // punish 惩罚页 URL 也包含滑块验证
            String url = browser.getCurrentUrl();
            if (!Strings.isEmpty(url) && url.contains("punish")) {
                return true;
            }
            String body = bodySnippet(browser);
            return containsAny(body, "请拖动下方滑块完成验证", "拖动滑块", "拖到最右边", "按住滑块", "验证失败");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测页面是否出现"验证失败，点击框体重试"提示，若有则点击 NC 验证框体触发重置。
     * 阿里云 NC 滑块验证失败后，需要点击容器区域重新加载滑块。
     */
    private void clickRetryContainerIfPresent(Browser browser, DebugRecorder debug, String stepTag, int attempt) {
        try {
            String body = bodySnippet(browser);
            if (!containsAny(body, "验证失败", "点击框体重试")) {
                return;
            }
            log.info(
                    "TB promotion slider verify failed hint detected, clicking container to retry, step={}, attempt={}",
                    stepTag, attempt);
            debug.snapshot(browser, stepTag + "-verify-failed-" + attempt);

            // 通过 JS 找到 NC 验证容器框体坐标（.nc-container 或 .nc_wrapper 或含"验证失败"文字的父级区域）
            Map<String, Object> containerInfo = browser.executeScript(
                    "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                            "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                            // 优先找 NC 容器
                            "var c=document.querySelector('.nc-container,.nc_wrapper,.sm-pop-inner');" +
                            "if(c&&visible(c)){var r=c.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height};}"
                            +
                            // 兜底：找含"验证失败"文字的可见元素
                            "var all=Array.prototype.slice.call(document.querySelectorAll('div,span,p'));" +
                            "for(var i=0;i<all.length;i++){" +
                            "  var t=(all[i].innerText||all[i].textContent||'').trim();" +
                            "  if(t.indexOf('验证失败')>=0&&visible(all[i])){" +
                            "    var r=all[i].getBoundingClientRect();" +
                            "    if(r.width>50&&r.height>20){return {x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height};}"
                            +
                            "  }" +
                            "}" +
                            "return null;");

            if (containerInfo == null) {
                log.warn("TB promotion verify-failed container not found, step={}, attempt={}", stepTag, attempt);
                return;
            }

            double cx = toDouble(containerInfo.get("x"));
            double cy = toDouble(containerInfo.get("y"));
            if (cx < 1 || cy < 1) {
                log.warn("TB promotion verify-failed container coordinates invalid, x={}, y={}", cx, cy);
                return;
            }

            log.info("TB promotion clicking verify-failed container at ({}, {}), step={}, attempt={}", cx, cy, stepTag,
                    attempt);
            // 用 mouseDrag 原地点击（起点终点相同 = 点击效果），比 elementClick 更自然
            browser.mouseDrag(cx, cy, cx, cy, 1);
            Extends.sleep(1500);
            debug.snapshot(browser, stepTag + "-verify-retry-clicked-" + attempt);
        } catch (Exception e) {
            log.warn("TB promotion click retry container error, step={}, attempt={}, error={}", stepTag, attempt,
                    e.getMessage());
        }
    }

    /**
     * 模拟人工缓慢向右拖拽阿里妈妈滑块验证的滑块按钮。
     * 通过 JS 找到滑块元素坐标，再用 Playwright 原生 Mouse API 模拟拖拽。
     * 返回 true 表示已成功模拟拖拽（不代表验证通过），false 表示未找到滑块。
     * 失败时保存当前页面 HTML 快照，便于 debug。
     */
    private boolean simulateSlideDrag(Browser browser, TbPromotionConfig config,
            DebugRecorder debug, String stepTag, int attempt) {
        // 找到滑块按钮，返回 {x, y, width, height, trackWidth}，用于计算拖拽距离
        Map<String, Object> sliderInfo = browser.executeScript(
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                        "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                        "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                        // 1) 首先精确匹配阿里云 NoCaptcha (NC) 滑块把手: class 含 btn_slide
                        "var handles=Array.prototype.slice.call(document.querySelectorAll('.btn_slide,[class*=btn_slide]')).filter(visible);"
                        +
                        // 2) NC 轨道: .nc_scale
                        "var tracks=Array.prototype.slice.call(document.querySelectorAll('.nc_scale,[class*=nc_scale],[class*=nc_wrapper]')).filter(function(e){"
                        +
                        "  if(!visible(e)){return false;}var r=e.getBoundingClientRect();return r.width>100;" +
                        "});" +
                        // 3) 通用备选: class 含 slider/handle/drag 且宽度 <= 80
                        "if(handles.length===0){" +
                        "  handles=Array.prototype.slice.call(document.querySelectorAll(" +
                        "    '[class*=slider],[class*=Slider],[class*=handle],[class*=Handle],[class*=drag],[class*=Drag]'"
                        +
                        "  )).filter(function(e){" +
                        "    if(!visible(e)){return false;}var r=e.getBoundingClientRect();" +
                        "    return r.width>0&&r.width<=80&&r.height>0&&r.height<=80;" +
                        "  });" +
                        "}" +
                        // 4) 轨道备选: 宽度较大且 class 含 track/rail/groove
                        "if(tracks.length===0){" +
                        "  tracks=Array.prototype.slice.call(document.querySelectorAll(" +
                        "    '[class*=track],[class*=Track],[class*=rail],[class*=Rail],[class*=groove],[class*=Groove]'"
                        +
                        "  )).filter(function(e){" +
                        "    if(!visible(e)){return false;}var r=e.getBoundingClientRect();return r.width>100;" +
                        "  });" +
                        "}" +
                        // 5) 最终兑底: 找到文字含'拖'的父容器内第一个小方块
                        "if(handles.length===0){" +
                        "  var verifyBox=null;" +
                        "  var allDivs=Array.prototype.slice.call(document.querySelectorAll('div,span,section'));" +
                        "  for(var i=0;i<allDivs.length;i++){" +
                        "    var t=norm(allDivs[i].innerText||allDivs[i].textContent||'');" +
                        "    if(t.indexOf('\u62d6')>=0&&t.indexOf('\u6ed1\u5757')>=0){verifyBox=allDivs[i];break;}" +
                        "  }" +
                        "  if(verifyBox){" +
                        "    var kids=Array.prototype.slice.call(verifyBox.querySelectorAll('*'));" +
                        "    for(var j=0;j<kids.length;j++){" +
                        "      var kr=kids[j].getBoundingClientRect();" +
                        "      if(visible(kids[j])&&kr.width>10&&kr.width<=80&&kr.height>10&&kr.height<=80){" +
                        "        handles.push(kids[j]);break;" +
                        "      }" +
                        "    }" +
                        "  }" +
                        "}" +
                        "if(handles.length===0){return null;}" +
                        "var handle=handles[0];" +
                        "var hr=handle.getBoundingClientRect();" +
                        "var trackWidth=hr.width;" + // 默认以 handle 宽度兜底
                        "if(tracks.length>0){" +
                        "  var tr=tracks[0].getBoundingClientRect();" +
                        "  trackWidth=tr.width;" +
                        "}" +
                        "return {x:hr.left+hr.width/2,y:hr.top+hr.height/2,width:hr.width,height:hr.height,trackWidth:trackWidth};");

        if (sliderInfo == null) {
            log.warn("TB promotion slider handle not found, cannot simulate drag, step={}, attempt={}", stepTag,
                    attempt);
            // 保存当前页 HTML 便于 debug 排查滑块元素结构
            debug.snapshot(browser, stepTag + "-slide-no-handle-" + attempt);
            return false;
        }

        double startX = toDouble(sliderInfo.get("x"));
        double startY = toDouble(sliderInfo.get("y"));
        double handleWidth = toDouble(sliderInfo.get("width"));
        double trackWidth = toDouble(sliderInfo.get("trackWidth"));

        // 坐标合法性校验：NaN/Infinity 会导致 Playwright Mouse API 异常；(0,0) 也是非法滑块位置
        if (!Double.isFinite(startX) || !Double.isFinite(startY) || startX < 1 || startY < 1) {
            log.warn(
                    "TB promotion slider handle coordinates invalid, startX={}, startY={}, step={}, attempt={}, skip drag",
                    startX, startY, stepTag, attempt);
            // 保存当前页 HTML 便于 debug 排查坐标问题
            debug.snapshot(browser, stepTag + "-slide-invalid-coord-" + attempt);
            return false;
        }

        // 拖拽距离 = 轨道宽 - 滑块宽，兜底用视口宽度的 70%
        double dragDistance;
        if (trackWidth > handleWidth + 10) {
            dragDistance = trackWidth - handleWidth - 2;
        } else {
            // 轨道宽未找到或异常，用视口宽度兜底
            double viewportWidth = toDouble(browser.executeScript("return window.innerWidth || 800;"));
            dragDistance = Math.max(200, viewportWidth * 0.70 - handleWidth);
        }

        log.info(
                "TB promotion simulate slide drag, startX={}, startY={}, handleWidth={}, trackWidth={}, dragDistance={}",
                startX, startY, handleWidth, trackWidth, dragDistance);

        // 使用 Playwright 原生 Mouse API 模拟拖拽（真实浏览器输入事件，比 JS dispatchEvent 更难被检测）
        double endX = startX + dragDistance;
        browser.mouseDrag(startX, startY, endX, startY, 30);
        return true;
    }

    /** Object -> double 工具方法，兼容 Integer / Long / Double / String */
    private double toDouble(Object val) {
        if (val == null)
            return 0;
        if (val instanceof Number)
            return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean completeForwardLanding(Browser browser, TbPromotionConfig config) throws TimeoutException {
        String currentUrl = browser.getCurrentUrl();
        String body = bodySnippet(browser);
        if (!isForwardLandingUrl(currentUrl) && !hasQuickEntrance(body)) {
            return false;
        }
        long deadline = System.currentTimeMillis() + Math.max(10, config.getPageTimeoutSeconds()) * 1000L;
        boolean clicked = false;
        long lastClickAt = 0L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.completeForwardLanding");
            currentUrl = browser.getCurrentUrl();
            body = bodySnippet(browser);
            boolean forwardLanding = isForwardLandingUrl(currentUrl);
            boolean hasQuickEntrance = hasQuickEntrance(body);
            if (!forwardLanding && !hasQuickEntrance && isLoggedInUrl(currentUrl, config)) {
                return true;
            }
            if (!forwardLanding && !hasQuickEntrance && containsAny(body, "阿里妈妈", "淘宝联盟", "效果报表", "订单")) {
                return true;
            }
            if (hasQuickEntrance
                    && System.currentTimeMillis() - lastClickAt >= Math.max(3000L, config.nextStepDelayMillis() * 3L)
                    && nativeClickQuickEnter(browser)) {
                clicked = true;
                lastClickAt = System.currentTimeMillis();
                log.info("TB promotion quick-enter clicked, currentUrl={}", currentUrl);
                if (waitForwardLandingResolvedAfterClick(browser, config, deadline)) {
                    return true;
                }
                continue;
            }
            Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
        }
        String forwardUrl = readForwardUrl(browser);
        if (!Strings.isEmpty(forwardUrl)) {
            browser.navigateUrl(forwardUrl, Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
            Extends.sleep(config.nextStepDelayMillis());
            return waitForwardLandingResolvedAfterClick(browser, config,
                    System.currentTimeMillis() + Math.max(10, config.getPageTimeoutSeconds()) * 1000L);
        }
        return clicked;
    }

    private boolean waitForwardLandingResolvedAfterClick(Browser browser, TbPromotionConfig config, long outerDeadline)
            throws TimeoutException {
        long deadline = Math.min(outerDeadline,
                System.currentTimeMillis() + Math.max(8000L, config.nextStepDelayMillis() * 8L));
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.waitForwardLandingResolvedAfterClick");
            Extends.sleep(Math.max(700L, config.nextStepDelayMillis()));
            String currentUrl = browser.getCurrentUrl();
            String body = bodySnippet(browser);
            boolean forwardLanding = isForwardLandingUrl(currentUrl);
            boolean hasQuickEntrance = hasQuickEntrance(body);
            if (!forwardLanding && !hasQuickEntrance && isLoggedInUrl(currentUrl, config)) {
                return true;
            }
            if (!forwardLanding && !hasQuickEntrance && containsAny(body, "阿里妈妈", "淘宝联盟", "效果报表", "订单")) {
                return true;
            }
            if (hasQuickEntrance) {
                return false;
            }
        }
        return false;
    }

    private boolean nativeClickQuickEnter(Browser browser) {
        String selector = "[data-rx-tb-quick-enter='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-quick-enter';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                "if(document.readyState==='loading'){return false;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}"
                +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,[role=\"button\"],span,div'));"
                +
                "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=text(e);if(t!=='快速进入'&&t!=='快速登录'&&t.indexOf('快速进入')<0&&t.indexOf('快速登录')<0){continue;}"
                +
                "var target=e.closest('button,a,[role=\"button\"]')||e,r=target.getBoundingClientRect(),score=r.width*r.height/1000+(t==='快速进入'?0:80);if(score<bestScore){best=target;bestScore=score;}}"
                +
                "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return Boolean.TRUE.equals(browser.executeScript(
                    "var target=document.querySelector(arguments[0]);if(!target){return false;}target.click();return true;",
                    selector));
        }
    }

    private String readForwardUrl(Browser browser) {
        String url = browser.executeScript("try{" +
                "var u=new URL(location.href),f=u.searchParams.get('forward');" +
                "return f?decodeURIComponent(f):'';" +
                "}catch(e){return '';}");
        return url == null ? "" : url;
    }

    private boolean waitOrderPageReady(Browser browser, TbPromotionConfig config) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getInitialPageTimeoutSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.waitOrderPageReady");
            Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
            String body = bodySnippet(browser);
            if (containsAny(body, "付款时间", "搜索订单编号", "查找订单", "订单状态", "佣金比例")) {
                return true;
            }
        }
        return false;
    }

    private boolean isOrderListLoaded(Browser browser) {
        String body = bodySnippet(browser);
        return containsAny(body, "订单信息", "订单状态", "总提成率", "付款预估收入", "暂无数据")
                && containsAny(body, "上一页", "下一页", "子订单编号", "父订单编号", "暂无数据");
    }

    private boolean selectPaymentDateRange(Browser browser, LocalDate startDate, LocalDate endDate,
            TbPromotionConfig config,
            DebugRecorder debug) throws TimeoutException {
        if (!nativeClickPaymentTimeRange(browser)) {
            debug.snapshot(browser, "03-date-range-open-click-missing");
            return false;
        }
        Extends.sleep(Math.max(600, config.nextStepDelayMillis() / 2L));
        debug.snapshot(browser, "03-date-range-after-open-click");

        List<String> months = waitDatePickerMonths(browser, config);
        if (months.isEmpty() && !isDateRangePopupOpen(browser) && nativeClickPaymentTimeRange(browser)) {
            Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
            debug.snapshot(browser, "03-date-range-after-open-retry-click");
            months = waitDatePickerMonths(browser, config);
        }
        if (months.isEmpty()) {
            if (!isDateRangePopupOpen(browser)) {
                debug.snapshot(browser, "03-date-range-popup-closed-after-open");
                return false;
            }
            debug.snapshot(browser, "03-date-range-before-inner-input-click");
            if (!nativeClickDateInputInRangePopup(browser)) {
                debug.snapshot(browser, "03-date-range-inner-input-missing");
                return false;
            }
            Extends.sleep(Math.max(600, config.nextStepDelayMillis() / 2L));
            debug.snapshot(browser, "03-date-range-after-inner-input-click");
            months = waitDatePickerMonths(browser, config);
            if (months.isEmpty()) {
                debug.snapshot(browser, "03-date-range-months-empty");
                return false;
            }
        }

        YearMonth startMonth = YearMonth.from(startDate);
        YearMonth endMonth = YearMonth.from(endDate);
        boolean startMonthReady = false;
        for (int i = 0; i < 36; i++) {
            ensureTaskDeadline("getTbPromotionOrders.selectStartMonth");
            months = readDatePickerMonths(browser);
            if (months.isEmpty()) {
                debug.snapshot(browser, "03-date-range-start-month-empty");
                return false;
            }
            YearMonth left = parseYearMonthText(months.get(0));
            if (startMonth.equals(left)) {
                startMonthReady = true;
                break;
            }
            boolean clicked = left != null && left.isAfter(startMonth)
                    ? nativeClickDatePickerNav(browser, false)
                    : nativeClickDatePickerNav(browser, true);
            if (!clicked) {
                debug.snapshot(browser, "03-date-range-start-month-nav-missing");
                return false;
            }
            Extends.sleep(config.nextStepDelayMillis());
        }
        if (!startMonthReady || !nativeClickDateInMonth(browser, startDate)) {
            debug.snapshot(browser, "03-date-range-start-day-missing");
            return false;
        }
        Extends.sleep(config.nextStepDelayMillis());
        debug.snapshot(browser, "03-date-range-start-day-selected");

        months = readDatePickerMonths(browser);
        YearMonth left = months.isEmpty() ? null : parseYearMonthText(months.get(0));
        if (endMonth.equals(left)) {
            boolean clicked = nativeClickDateInMonth(browser, endDate);
            if (!clicked) {
                debug.snapshot(browser, "03-date-range-end-day-left-missing");
            }
            return clicked && finishDateRangeSelection(browser, startDate, endDate, config, debug);
        }
        boolean endMonthReady = false;
        for (int i = 0; i < 36; i++) {
            ensureTaskDeadline("getTbPromotionOrders.selectEndMonth");
            months = readDatePickerMonths(browser);
            if (months.isEmpty()) {
                debug.snapshot(browser, "03-date-range-end-month-empty");
                return false;
            }
            YearMonth right = months.size() > 1 ? parseYearMonthText(months.get(1)) : parseYearMonthText(months.get(0));
            if (endMonth.equals(right)) {
                endMonthReady = true;
                break;
            }
            boolean clicked = right != null && right.isAfter(endMonth)
                    ? nativeClickDatePickerNav(browser, false)
                    : nativeClickDatePickerNav(browser, true);
            if (!clicked) {
                debug.snapshot(browser, "03-date-range-end-month-nav-missing");
                return false;
            }
            Extends.sleep(config.nextStepDelayMillis());
        }
        boolean clicked = endMonthReady && nativeClickDateInMonth(browser, endDate);
        if (!clicked) {
            debug.snapshot(browser, "03-date-range-end-day-missing");
        }
        return clicked && finishDateRangeSelection(browser, startDate, endDate, config, debug);
    }

    private boolean finishDateRangeSelection(Browser browser, LocalDate startDate, LocalDate endDate,
            TbPromotionConfig config, DebugRecorder debug) throws TimeoutException {
        if (!nativeClickDateRangeConfirmIfPresent(browser)) {
            debug.snapshot(browser, "03-date-range-confirm-missing");
            return false;
        }
        if (waitPaymentDateRangeSelected(browser, startDate, endDate, config)) {
            return true;
        }
        if (isDateRangePopupOpen(browser)) {
            nativeClickDateRangeConfirmIfPresent(browser);
            if (waitPaymentDateRangeSelected(browser, startDate, endDate, config)) {
                return true;
            }
        }
        debug.snapshot(browser, "03-date-range-selected-not-applied");
        return false;
    }

    private boolean waitPaymentDateRangeSelected(Browser browser, LocalDate startDate, LocalDate endDate,
            TbPromotionConfig config) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(5000L, config.nextStepDelayMillis() * 5L);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.waitPaymentDateRangeSelected");
            if (isPaymentDateRangeSelected(browser, startDate, endDate)) {
                return true;
            }
            Extends.sleep(500L);
        }
        return false;
    }

    private boolean isPaymentDateRangeSelected(Browser browser, LocalDate startDate, LocalDate endDate) {
        Boolean selected = browser.executeScript(JS_VISIBLE_STRICT +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var start=arguments[0],end=arguments[1];" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('.mux-calendar-label-container'));" +
                "for(var i=0;i<labels.length;i++){var e=labels[i];if(!visible(e)||e.closest('.mux-tooltip,.mux-picker-dropdown,.mux-picker-panel-container')){continue;}"
                +
                "var t=norm(e.innerText||e.textContent);if(t.indexOf(start)>=0&&t.indexOf(end)>=0){return true;}}" +
                "return false;", startDate.toString(), endDate.toString());
        return Boolean.TRUE.equals(selected);
    }

    private List<String> waitDatePickerMonths(Browser browser, TbPromotionConfig config) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(2500L, config.nextStepDelayMillis() * 3L);
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.waitDatePickerMonths");
            List<String> months = readDatePickerMonths(browser);
            if (!months.isEmpty()) {
                return months;
            }
            Extends.sleep(300L);
        }
        return new ArrayList<String>();
    }

    private boolean isDateRangePopupOpen(Browser browser) {
        Boolean open = browser.executeScript(JS_VISIBLE_STRICT +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('[class*=calendar],[class*=Calendar],[class*=picker],[class*=Picker],[class*=dropdown],[class*=Dropdown],[class*=overlay],[class*=Overlay],[class*=popup],[class*=Popup]'));"
                +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var r=e.getBoundingClientRect(),st=getComputedStyle(e),c=((e.className||'')+'').toLowerCase();"
                +
                "if(r.width<160||r.height<60){continue;}if(st.position!=='absolute'&&st.position!=='fixed'&&c.indexOf('overlay')<0&&c.indexOf('dropdown')<0&&c.indexOf('popup')<0){continue;}"
                +
                "var t=norm(e.innerText||e.textContent);if(t.indexOf('选择时间')>=0||/\\d{4}年\\d{1,2}月/.test(t)||t.indexOf('开始')>=0||t.indexOf('结束')>=0){return true;}}"
                +
                "return false;");
        return Boolean.TRUE.equals(open);
    }

    private boolean nativeClickPaymentTimeRange(Browser browser) {
        String selector = "[data-rx-tb-payment-time-range='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-payment-time-range';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}"
                +
                "function rect(el){return el.getBoundingClientRect();}" +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('placeholder')||el.getAttribute('title')||el.getAttribute('aria-label'));}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,button,a,span,div,[role=\"combobox\"]'));"
                +
                "var direct=null;for(var i=0;i<nodes.length;i++){var e=nodes[i],t=text(e);if(visible(e)&&(t==='时间范围'||t.indexOf('时间范围')>=0)){direct=e;break;}}"
                +
                "if(!direct){var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p')),label=null;"
                +
                "for(var l=0;l<labels.length;l++){var lt=text(labels[l]);if(visible(labels[l])&&lt.indexOf('付款时间')>=0&&lt.length<=12){label=labels[l];break;}}"
                +
                "if(label){var lr=rect(label),best=null,bestScore=999999;" +
                "for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n)||n===label||label.contains(n)){continue;}var nr=rect(n),t=text(n);"
                +
                "var dy=Math.abs((nr.top+nr.bottom)/2-(lr.top+lr.bottom)/2),dx=nr.left-lr.right;" +
                "if(dx>=-30&&dy<80&&(/时间|日期|开始|结束|range|date/i.test(t+' '+n.className+' '+n.id)||nr.width>80)){var score=dy*20+Math.max(0,dx)+nr.width/20;if(score<bestScore){best=n;bestScore=score;}}}"
                +
                "direct=best;}}" +
                "if(!direct){return false;}var target=null;" +
                "if(direct.matches&&direct.matches('.mux-calendar-label-container')){target=direct;}" +
                "if(!target&&direct.querySelector){target=direct.querySelector('.mux-calendar-label-container');}" +
                "if(!target&&direct.closest){target=direct.closest('.mux-calendar-label-container');}" +
                "if(!target&&direct.closest){var wrap=direct.closest('.mux-calendar-dropdown-wrapper');if(wrap){target=wrap.querySelector('.mux-calendar-label-container')||wrap;}}"
                +
                "if(!target){target=(direct.querySelector&&direct.querySelector('.next-date-picker,.next-range-picker,.ant-picker,.el-date-editor,input,[role=\"combobox\"]'))||direct.closest('.next-select,.next-date-picker,.next-range-picker,.ant-picker,.el-date-editor,[role=\"combobox\"],button,a')||direct;}"
                +
                "target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return Boolean.TRUE.equals(browser.executeScript(
                    "var target=document.querySelector(arguments[0]);if(!target){return false;}target.click();return true;",
                    selector));
        }
    }

    private boolean nativeClickDateInputInRangePopup(Browser browser) {
        String selector = "[data-rx-tb-date-input='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-date-input';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                JS_VISIBLE_STRICT +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function scoreRoot(el){var t=norm(el.innerText||el.textContent);return t.indexOf('选择日期')>=0||t.indexOf('选择时间')>=0||t.indexOf('快捷日期')>=0?0:200;}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.mux-calendar-dropdown-overlay .mux-picker-input-active input,.mux-calendar-dropdown-overlay .mux-picker-input input,input,.next-date-picker,.next-range-picker,.ant-picker,.el-date-editor,[role=\"combobox\"]'));"
                +
                "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)||e.disabled){continue;}var p=e,score=999;"
                +
                "for(var d=0;p&&d<6;d++,p=p.parentElement){score=Math.min(score,scoreRoot(p));}" +
                "var ph=norm(e.getAttribute&&e.getAttribute('placeholder'));if(ph.indexOf('开始日期')>=0){score-=30;}if((e.className||'').indexOf('active')>=0){score-=20;}"
                +
                "var r=e.getBoundingClientRect();score+=r.top/100+r.left/1000;if(score<bestScore){best=e;bestScore=score;}}"
                +
                "if(!best){return false;}var target=best.closest('.next-date-picker,.next-range-picker,.ant-picker,.el-date-editor,[role=\"combobox\"]')||best;"
                +
                "target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return Boolean.TRUE.equals(browser.executeScript(
                    "var target=document.querySelector(arguments[0]);if(!target){return false;}target.click();return true;",
                    selector));
        }
    }

    private boolean nativeClickDateRangeConfirmIfPresent(Browser browser) {
        String selector = "[data-rx-tb-date-confirm='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-date-confirm';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                JS_VISIBLE_STRICT +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var overlays=Array.prototype.slice.call(document.querySelectorAll('.mux-calendar-dropdown-overlay,[class*=calendar][class*=overlay],[class*=picker][class*=dropdown]')).filter(visible);"
                +
                "if(overlays.length===0){return null;}" +
                "var root=overlays[0],nodes=Array.prototype.slice.call(root.querySelectorAll('button,a,[role=\"button\"],span,div'));"
                +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)||e.disabled||e.getAttribute('aria-disabled')==='true'){continue;}var t=norm(e.innerText||e.textContent||e.value||e.getAttribute('title')||e.getAttribute('aria-label'));if(t==='确定'){var target=e.closest('button,a,[role=\"button\"]')||e;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;}}"
                +
                "return false;");
        if (marked == null) {
            return true;
        }
        if (!Boolean.TRUE.equals(marked)) {
            return true;
        }
        try {
            browser.elementClick(selector, false);
            Extends.sleep(500L);
            return true;
        } catch (Exception e) {
            return Boolean.TRUE.equals(browser.executeScript(
                    "var target=document.querySelector(arguments[0]);if(!target){return false;}target.click();return true;",
                    selector));
        }
    }

    private List<String> readDatePickerMonths(Browser browser) {
        List<String> months = browser.executeScript(JS_VISIBLE_STRICT +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var items=[];" +
                "var pickers=Array.prototype.slice.call(document.querySelectorAll('.mux-picker-dropdown,.mux-picker-panel-container,.mux-picker-panels')).filter(visible);"
                +
                "for(var p=0;p<pickers.length;p++){var views=Array.prototype.slice.call(pickers[p].querySelectorAll('.mux-picker-header-view'));"
                +
                "for(var v=0;v<views.length;v++){var view=views[v];if(!visible(view)){continue;}var y=view.querySelector('.mux-picker-year-btn'),m=view.querySelector('.mux-picker-month-btn');"
                +
                "var yt=norm(y&&y.innerText||y&&y.textContent),mt=norm(m&&m.innerText||m&&m.textContent);if(/^\\d{4}年$/.test(yt)&&/^\\d{1,2}月$/.test(mt)){var vr=view.getBoundingClientRect();items.push({text:yt+mt,top:vr.top,left:vr.left});}}}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('div,span,button,th'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],t=norm(e.innerText||e.textContent||e.getAttribute('title'));"
                +
                "if(visible(e)&&/^\\d{4}年\\d{1,2}月$/.test(t)){var r=e.getBoundingClientRect();items.push({text:t,top:r.top,left:r.left});}}"
                +
                "items.sort(function(a,b){return Math.abs(a.top-b.top)>20?a.top-b.top:a.left-b.left;});" +
                "var out=[];for(var j=0;j<items.length;j++){if(out.indexOf(items[j].text)<0){out.push(items[j].text);}}"
                +
                "return out;");
        return months == null ? new ArrayList<String>() : months;
    }

    private boolean nativeClickDatePickerNav(Browser browser, boolean next) {
        String selector = "[data-rx-tb-picker-nav='1']";
        Boolean marked = browser.executeScript("var next=arguments[0],attr='data-rx-tb-picker-nav';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                JS_VISIBLE_STRICT +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function disabled(el){var c=((el.className||'')+'').toLowerCase();return !!el.disabled||el.getAttribute('aria-disabled')==='true'||/disabled|disable/.test(c);}"
                +
                "var picker=Array.prototype.slice.call(document.querySelectorAll('.mux-picker-dropdown,.mux-picker-panel-container')).filter(visible)[0];"
                +
                "if(picker){var muxNodes=Array.prototype.slice.call(picker.querySelectorAll(next?'.mux-picker-header-next-btn':'.mux-picker-header-prev-btn')).filter(function(e){return visible(e)&&!disabled(e)&&((e.className||'')+'').indexOf('super')<0;});"
                +
                "if(muxNodes.length>0){muxNodes[0].setAttribute(attr,'1');muxNodes[0].scrollIntoView({block:'center',inline:'center'});return true;}}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,span,i,div'));" +
                "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)||disabled(e)){continue;}var t=norm(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title),c=((e.className||'')+'').toLowerCase();"
                +
                "var ok=next?(t==='>'||t==='›'||/下个月|后一月|next|right|arrow-right/.test(t+' '+c)):(t==='<'||t==='‹'||/上个月|前一月|prev|left|arrow-left/.test(t+' '+c));"
                +
                "if(!ok){continue;}var r=e.getBoundingClientRect();if(r.width>90||r.height>90){continue;}var score=next?(window.innerWidth-r.left):r.left;if(score<bestScore){best=e;bestScore=score;}}"
                +
                "if(!best){return false;}var target=best.closest('button,a')||best;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;",
                next);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nativeClickDateInMonth(Browser browser, LocalDate date) {
        String selector = "[data-rx-tb-picker-day='1']";
        Boolean marked = browser.executeScript(
                "var monthText=arguments[0],day=String(arguments[1]),dateText=arguments[2],attr='data-rx-tb-picker-day';"
                        +
                        "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                        +
                        JS_VISIBLE_STRICT +
                        "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                        "function bad(el){var c=((el.className||'')+'').toLowerCase();return /disabled|prev|next|other|off|unavailable/.test(c)||el.getAttribute('aria-disabled')==='true'||(c.indexOf('mux-picker-cell')>=0&&c.indexOf('mux-picker-cell-in-view')<0);}"
                        +
                        "var picker=Array.prototype.slice.call(document.querySelectorAll('.mux-picker-dropdown,.mux-picker-panel-container')).filter(visible)[0];"
                        +
                        "if(picker){var exact=Array.prototype.slice.call(picker.querySelectorAll('td[title=\"'+dateText+'\"],td[title=\"'+dateText+'\"] .mux-picker-cell-inner')).filter(function(e){var cell=e.closest('td')||e;return visible(e)&&visible(cell)&&!bad(cell);});"
                        +
                        "if(exact.length>0){var target=exact[0].closest('td,button,a')||exact[0];target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;}}"
                        +
                        "var headers=Array.prototype.slice.call(document.querySelectorAll('div,span,button,th')).filter(function(e){return visible(e)&&norm(e.innerText||e.textContent||e.title)===monthText;});"
                        +
                        "if(headers.length===0){return false;}headers.sort(function(a,b){var ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();return Math.abs(ar.top-br.top)>20?ar.top-br.top:ar.left-br.left;});"
                        +
                        "var h=headers[0],hr=h.getBoundingClientRect(),root=h;for(var d=0;root.parentElement&&d<8;d++,root=root.parentElement){var rr=root.getBoundingClientRect(),txt=norm(root.innerText||root.textContent);if(txt.indexOf(monthText)>=0&&rr.width>=180&&rr.width<=900&&rr.height>=180&&rr.height<=700){break;}}"
                        +
                        "var nodes=Array.prototype.slice.call(root.querySelectorAll('td,button,span,div'));" +
                        "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)||bad(e)){continue;}var t=norm(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title);if(t!==day){continue;}var r=e.getBoundingClientRect();if(r.top<hr.bottom-5||r.width>80||r.height>80){continue;}var score=Math.abs((r.left+r.right)/2-(hr.left+hr.right)/2)+Math.abs(r.top-hr.bottom);if(score<bestScore){best=e;bestScore=score;}}"
                        +
                        "if(!best){return false;}var target=best.closest('td,button,a')||best;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;",
                formatMonth(date), date.getDayOfMonth(), date.toString());
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nativeClickOrderSearchButton(Browser browser) {
        String selector = "[data-rx-tb-order-search='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-order-search';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}"
                +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('aria-label')||el.getAttribute('placeholder'));}"
                +
                "var inputs=Array.prototype.slice.call(document.querySelectorAll('input,textarea')).filter(function(e){return visible(e)&&text(e).indexOf('搜索订单编号')>=0;});"
                +
                "var input=inputs.length>0?inputs[0]:null,ir=input?input.getBoundingClientRect():null;" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,span,div,[role=\"button\"]'));"
                +
                "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=text(e);if(t!=='查找订单'&&t.indexOf('查找订单')<0){continue;}var target=e.closest('button,a,[role=\"button\"]')||e,r=target.getBoundingClientRect(),score=r.width*r.height/1000;"
                +
                "if(ir){score+=Math.abs(r.left-ir.right)+Math.abs((r.top+r.bottom-ir.top-ir.bottom)/2);}if(score<bestScore){best=target;bestScore=score;}}"
                +
                "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void waitOrderRowsSettled(Browser browser, TbPromotionConfig config) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(10, config.getPageTimeoutSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionOrders.waitOrderRowsSettled");
            Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
            String body = bodySnippet(browser);
            if (!containsAny(body, "加载中", "查询中") && containsAny(body, "订单", "暂无", "没有", "无数据", "佣金比例")) {
                return;
            }
        }
    }

    private List<TbPromotionOrderItem> readOrderRows(Browser browser) {
        List<Map<String, Object>> rows = browser
                .executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                        "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}"
                        +
                        "function text(el){return norm(el.innerText||el.textContent||el.value||'');}" +
                        "function lines(el){return (el.innerText||el.textContent||'').split(/\\n+/).map(norm).filter(function(x){return !!x;});}"
                        +
                        "function cellList(row){var out=[];var kids=Array.prototype.slice.call(row.children||[]);for(var i=0;i<kids.length;i++){var k=kids[i],c=((k.className||'')+'').toLowerCase();if(visible(k)&&(k.tagName==='TD'||k.tagName==='TH'||c.indexOf('cell')>=0)){out.push(k);}}"
                        +
                        "if(out.length<5){out=Array.prototype.slice.call(row.querySelectorAll('td,.next-table-cell,[class*=\"table-cell\"],[class*=\"TableCell\"]')).filter(visible);}return out;}"
                        +
                        "function firstMoney(v){var m=String(v||'').match(/[￥¥]\\s*(-?\\d+(?:\\.\\d+)?)/);return m?'￥'+Number(m[1]).toFixed(2):'';}"
                        +
                        "function sumMoney(v,nullWhenDash){var s=String(v||'');var ms=s.match(/[￥¥]\\s*-?\\d+(?:\\.\\d+)?/g),sum=0,count=0;if(ms){for(var i=0;i<ms.length;i++){var n=ms[i].replace(/[￥¥\\s]/g,'');sum+=Number(n);count++;}}"
                        +
                        "if(count===0){var ns=s.match(/-?\\d+(?:\\.\\d+)?/g);if(ns){for(var j=0;j<ns.length;j++){sum+=Number(ns[j]);count++;}}}"
                        +
                        "if(count===0&&nullWhenDash&&s.indexOf('--')>=0){return null;}return count===0?'':'￥'+sum.toFixed(2);}"
                        +
                        "function firstDate(v){var m=String(v||'').match(/\\d{4}-\\d{2}-\\d{2}(?:\\s+\\d{2}:\\d{2}:\\d{2})?/);return m?m[0]:'';}"
                        +
                        "function label(v,re){var m=String(v||'').match(re);return m?norm(m[1]||m[0]):'';}" +
                        "function longNos(v){return String(v||'').match(/\\b[A-Za-z0-9_-]{10,}\\b/g)||[];}" +
                        "function productName(cell){var as=Array.prototype.slice.call(cell.querySelectorAll('a[href]')).filter(visible),best='',href='',score=-1;for(var i=0;i<as.length;i++){var t=text(as[i]),h=as[i].href||as[i].getAttribute('href')||'';if(!t||/订单|主单|复制|查看/.test(t)){continue;}if(t.length>score){best=t;href=h;score=t.length;}}"
                        +
                        "if(best){return {name:best,link:href};}var ls=lines(cell);return {name:ls.length>0?ls[0]:'',link:''};}"
                        +
                        "function parse(row){var cells=cellList(row);if(cells.length<5){return null;}var first=cells[0],ft=text(first),ls=lines(first),p=productName(first),main=label(ft,/(?:父订单编号|父订单号|主单号|主订单号|主订单编号)[:：\\s]*([A-Za-z0-9_-]{8,})/),order=label(ft,/(?:子订单编号|子订单号)[:：\\s]*([A-Za-z0-9_-]{8,})/);"
                        +
                        "if(!order){order=label(ft,/(?:^|[^父子主])(?:订单号|订单编号)[:：\\s]*([A-Za-z0-9_-]{8,})/);}" +
                        "if(!main||!order){var nos=longNos(ft);if(!main&&nos.length>0){main=nos[0];}if(!order&&nos.length>1){order=nos[1];}else if(!order&&nos.length>0){order=nos[nos.length-1];}}"
                        +
                        "var store='';for(var i=0;i<ls.length;i++){var line=ls[i];if(line===p.name||/订单|主单|￥|¥|\\d{4}-\\d{2}-\\d{2}/.test(line)){continue;}if(!store||/店|铺/.test(line)){store=line;if(/店|铺/.test(line)){break;}}}"
                        +
                        "store=store.replace(/^店铺名[:：\\s]*/,'');" +
                        "var estBilling=firstMoney(ft),actual=sumMoney(text(cells[4]),true);" +
                        "return {productName:p.name,productLink:p.link,storeName:store,mainOrderNo:main,orderNo:order,estimatedBillingAmount:estBilling,orderTime:firstDate(ft),orderStatus:text(cells[1]),commissionRate:(text(cells[2]).match(/\\d+(?:\\.\\d+)?%/)||[''])[0],estimatedCommission:sumMoney(text(cells[3]),false),actualCommission:actual,actualBillingAmount:actual==null?null:estBilling};}"
                        +
                        "var raw=Array.prototype.slice.call(document.querySelectorAll('tbody tr,.next-table-row,[class*=\"table-row\"],[class*=\"TableRow\"]')).filter(visible);"
                        +
                        "var out=[],seen={};for(var i=0;i<raw.length;i++){var rt=text(raw[i]);if(!rt||rt.indexOf('暂无')>=0||rt.indexOf('无数据')>=0){continue;}if((rt.indexOf('订单信息')>=0&&rt.indexOf('订单状态')>=0&&!/\\d{4}-\\d{2}-\\d{2}/.test(rt))||(rt.indexOf('订单状态')>=0&&(rt.indexOf('佣金比例')>=0||rt.indexOf('总提成率')>=0)&&!/\\d{4}-\\d{2}-\\d{2}/.test(rt))){continue;}if(rt.indexOf('订单')<0&&rt.indexOf('￥')<0&&rt.indexOf('¥')<0){continue;}var item=parse(raw[i]);if(!item||(!item.orderNo&&!item.mainOrderNo&&!item.productName)){continue;}var key=(item.orderNo||'')+'|'+(item.mainOrderNo||'')+'|'+(item.orderTime||'')+'|'+(item.estimatedCommission||'');if(seen[key]){continue;}seen[key]=true;out.push(item);}return out;");
        if (rows != null && !rows.isEmpty()) {
            List<TbPromotionOrderItem> items = new ArrayList<TbPromotionOrderItem>();
            for (Map<String, Object> row : rows) {
                items.add(objectMapper.convertValue(row, TbPromotionOrderItem.class));
            }
            return items;
        }
        return parseOrderRowsFromHtml(readOrderListOuterHtml(browser));
    }

    private String readOrderListOuterHtml(Browser browser) {
        String html = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}"
                +
                "function score(el){var t=norm(el.innerText||el.textContent),s=0;if(t.indexOf('订单状态')>=0){s+=20;}if(t.indexOf('佣金比例')>=0){s+=20;}if(t.indexOf('预估')>=0){s+=10;}if(t.indexOf('实际')>=0){s+=10;}s+=el.querySelectorAll('tbody tr,.next-table-row,[class*=\"table-row\"],[class*=\"TableRow\"]').length;return s;}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.next-table,table,[class*=\"table\"],[class*=\"Table\"]')).filter(visible);"
                +
                "var best=null,bestScore=-1;for(var i=0;i<nodes.length;i++){var sc=score(nodes[i]);if(sc>bestScore){best=nodes[i];bestScore=sc;}}"
                +
                "return best&&bestScore>0?best.outerHTML:'';");
        return html == null ? "" : html;
    }

    private List<TbPromotionOrderItem> parseOrderRowsFromHtml(String html) {
        if (Strings.isEmpty(html)) {
            return new ArrayList<TbPromotionOrderItem>();
        }
        try {
            Document document = Jsoup.parse(html);
            Elements rows = document.select("tbody tr, .next-table-row, [class*=table-row], [class*=TableRow]");
            List<TbPromotionOrderItem> items = new ArrayList<TbPromotionOrderItem>();
            for (Element row : rows) {
                String rowText = normalizeText(row.text());
                if (Strings.isEmpty(rowText) || containsAny(rowText, "暂无", "无数据", "没有")) {
                    continue;
                }
                if ((rowText.contains("订单信息") && rowText.contains("订单状态") && !DATE_TIME_PATTERN.matcher(rowText).find())
                        || (rowText.contains("订单状态") && (rowText.contains("佣金比例") || rowText.contains("总提成率"))
                                && !DATE_TIME_PATTERN.matcher(rowText).find())) {
                    continue;
                }
                Elements cells = rowCells(row);
                if (cells.size() < 5) {
                    continue;
                }
                TbPromotionOrderItem item = parseOrderCells(cells);
                if (Strings.isEmpty(item.getOrderNo()) && Strings.isEmpty(item.getMainOrderNo())
                        && Strings.isEmpty(item.getProductName())) {
                    continue;
                }
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.debug("Parse TB promotion order rows from html fail, error={}", e.getMessage());
            return new ArrayList<TbPromotionOrderItem>();
        }
    }

    private Elements rowCells(Element row) {
        Elements cells = new Elements();
        for (Element child : row.children()) {
            String className = child.className() == null ? "" : child.className().toLowerCase(Locale.ROOT);
            if ("td".equalsIgnoreCase(child.tagName()) || "th".equalsIgnoreCase(child.tagName())
                    || className.contains("cell")) {
                cells.add(child);
            }
        }
        if (cells.size() < 5) {
            cells = row.select("td, .next-table-cell, [class*=table-cell], [class*=TableCell]");
        }
        return cells;
    }

    private TbPromotionOrderItem parseOrderCells(Elements cells) {
        TbPromotionOrderItem item = new TbPromotionOrderItem();
        Element first = cells.get(0);
        String firstText = normalizeText(first.text());
        fillProductInfo(item, first);
        fillOrderNumbers(item, firstText);
        item.setEstimatedBillingAmount(firstMoney(firstText));
        item.setOrderTime(firstMatch(firstText, DATE_TIME_PATTERN));
        item.setOrderStatus(cellTextAt(cells, 1));
        item.setCommissionRate(firstMatch(cellTextAt(cells, 2), Pattern.compile("\\d+(?:\\.\\d+)?%")));
        item.setEstimatedCommission(sumMoney(cellTextAt(cells, 3), false));
        String actualCommission = sumMoney(cellTextAt(cells, 4), true);
        item.setActualCommission(actualCommission);
        item.setActualBillingAmount(actualCommission == null ? null : item.getEstimatedBillingAmount());
        return item;
    }

    private void fillProductInfo(TbPromotionOrderItem item, Element cell) {
        String productName = "";
        String productLink = "";
        int bestScore = -1;
        for (Element link : cell.select("a[href]")) {
            String text = normalizeText(link.text());
            if (Strings.isEmpty(text) || containsAny(text, "订单", "主单", "复制", "查看")) {
                continue;
            }
            if (text.length() > bestScore) {
                bestScore = text.length();
                productName = text;
                productLink = normalizeText(link.attr("href"));
            }
        }
        List<String> lines = textLines(cell);
        if (Strings.isEmpty(productName) && !lines.isEmpty()) {
            productName = lines.get(0);
        }
        item.setProductName(productName);
        item.setProductLink(productLink);
        for (String line : lines) {
            if (line.equals(productName) || containsAny(line, "订单", "主单", "￥", "¥")
                    || DATE_TIME_PATTERN.matcher(line).find()) {
                continue;
            }
            if (Strings.isEmpty(item.getStoreName()) || containsAny(line, "店", "铺")) {
                item.setStoreName(line.replaceFirst("^店铺名[:：\\s]*", ""));
                if (containsAny(line, "店", "铺")) {
                    break;
                }
            }
        }
    }

    private void fillOrderNumbers(TbPromotionOrderItem item, String text) {
        item.setMainOrderNo(firstLabeled(text, "(?:父订单编号|父订单号|主单号|主订单号|主订单编号)[:：\\s]*([A-Za-z0-9_-]{8,})"));
        item.setOrderNo(firstLabeled(text, "(?:子订单编号|子订单号)[:：\\s]*([A-Za-z0-9_-]{8,})"));
        if (Strings.isEmpty(item.getOrderNo())) {
            item.setOrderNo(firstLabeled(text, "(?<![父子主])(?:订单号|订单编号)[:：\\s]*([A-Za-z0-9_-]{8,})"));
        }
        List<String> numbers = allMatches(text, LONG_NO_PATTERN);
        if (Strings.isEmpty(item.getMainOrderNo()) && !numbers.isEmpty()) {
            item.setMainOrderNo(numbers.get(0));
        }
        if (Strings.isEmpty(item.getOrderNo())) {
            if (numbers.size() > 1) {
                item.setOrderNo(numbers.get(1));
            } else if (!numbers.isEmpty()) {
                item.setOrderNo(numbers.get(numbers.size() - 1));
            }
        }
    }

    private void scrollOrderPageBottom(Browser browser, TbPromotionConfig config) throws TimeoutException {
        for (int i = 0; i < 2; i++) {
            ensureTaskDeadline("getTbPromotionOrders.scrollPageBottom");
            browser.executeScript(
                    "function canScroll(e){return e&&e.scrollHeight&&e.clientHeight&&e.scrollHeight>e.clientHeight+8;}"
                            +
                            "var roots=[document.scrollingElement,document.documentElement,document.body];" +
                            "Array.prototype.slice.call(document.querySelectorAll('*')).forEach(function(e){if(canScroll(e)){roots.push(e);}});"
                            +
                            "roots.forEach(function(e){try{e.scrollTop=e.scrollHeight;}catch(ex){}});" +
                            "window.scrollTo(0,Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));");
            Extends.sleep(Math.max(300, config.nextStepDelayMillis()));
        }
    }

    private boolean hasNextOrderPage(Browser browser) {
        Boolean ok = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}"
                +
                "function disabled(el){var c=((el.className||'')+'').toLowerCase();return !!el.disabled||el.getAttribute('aria-disabled')==='true'||/disabled|disable/.test(c);}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.next-pagination button,.next-pagination a,.next-pagination span,.next-pagination li,button,a,li,span'));"
                +
                "for(var i=nodes.length-1;i>=0;i--){var e=nodes[i];if(!visible(e)){continue;}var t=norm(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title),c=((e.className||'')+'').toLowerCase();"
                +
                "if(t.indexOf('下一页')>=0||/next/.test(c)){var target=e.closest('button,a,li')||e;if(!disabled(e)&&!disabled(target)){return true;}}}"
                +
                "return false;");
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickNextOrderPage(Browser browser) {
        String selector = "[data-rx-tb-next-page='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-next-page';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});"
                +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}"
                +
                "function disabled(el){var c=((el.className||'')+'').toLowerCase();return !!el.disabled||el.getAttribute('aria-disabled')==='true'||/disabled|disable/.test(c);}"
                +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.next-pagination button,.next-pagination a,.next-pagination span,.next-pagination li,button,a,li,span'));"
                +
                "for(var i=nodes.length-1;i>=0;i--){var e=nodes[i];if(!visible(e)){continue;}var t=norm(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title),c=((e.className||'')+'').toLowerCase();"
                +
                "if(t.indexOf('下一页')>=0||/next/.test(c)){var target=e.closest('button,a,li')||e;if(!disabled(e)&&!disabled(target)){target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;}}}"
                +
                "return false;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SneakyThrows
    private WebBrowserConfig createBrowserConfig(TbPromotionOrdersRequest request, TbPromotionConfig tbConfig) {
        WebBrowserConfig config = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        config.setProfileDataPath(profileManager.resolveProfileDataPath(request.getProfileName()));
        config.setHeadless(tbConfig.isHeadless());
        config.setFingerprintEnabled(tbConfig.isFingerprintEnabled());
        config.setFingerprintHeadless(tbConfig.isHeadless());
        if (Strings.isEmpty(config.getConfigureScriptExecutorType())) {
            config.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        }
        return config;
    }

    private TbPromotionOrdersRequest normalizeRequest(TbPromotionOrdersRequest request, TbPromotionConfig config) {
        if (request == null) {
            request = new TbPromotionOrdersRequest();
        }
        if (Strings.isEmpty(request.getStartTime()) || Strings.isEmpty(request.getEndTime())) {
            throw new InvalidException("startTime and endTime are required");
        }
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(Strings.isEmpty(config.getProfileName()) ? profileManager.defaultProfileName()
                    : config.getProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        if (Strings.isEmpty(request.getOutputPath())) {
            request.setOutputPath(config.getDefaultOutputPath());
        }
        LocalDate startDate = LocalDate.parse(request.getStartTime(), DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(request.getEndTime(), DATE_FORMATTER);
        if (startDate.isAfter(endDate)) {
            throw new InvalidException("startTime must be less than or equal to endTime");
        }
        return request;
    }

    private TbPromotionOrdersResult createResult(TbPromotionOrdersRequest request) {
        TbPromotionOrdersResult result = new TbPromotionOrdersResult();
        result.setTaskType(TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setStartTime(request.getStartTime());
        result.setEndTime(request.getEndTime());
        result.setProfileName(request.getProfileName());
        return result;
    }

    private void applyEntryResult(TbPromotionOrdersResult result, CrawlEntryResult entry) {
        result.setFingerprintPassed(entry.isFingerprintPassed());
        result.setCurrentUrl(entry.getCurrentUrl());
        result.setLoginRequired(entry.isLoginRequired());
        result.getDiagnostics().putAll(entry.getDiagnostics());
        if (!entry.isPassed()) {
            fail(result, entry.getStatus(), entry.getMessage());
        }
    }

    private boolean isLoginRequired(String currentUrl, TbPromotionConfig config) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("pub.alimama.com") && lower.contains("login_jump")) {
            return false;
        }
        return currentUrl.startsWith(config.getLoginUrlPrefix())
                || lower.contains("login.taobao.com")
                || lower.contains("havanalogin")
                || (lower.contains("/login") && !lower.contains("login_jump"));
    }

    private boolean isLoggedInUrl(String currentUrl, TbPromotionConfig config) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return lower.startsWith(config.getHomeUrl().toLowerCase(Locale.ROOT))
                || lower.startsWith(config.getOrderUrl().toLowerCase(Locale.ROOT))
                || lower.contains("pub.alimama.com/portal")
                || isForwardLandingUrl(currentUrl);
    }

    private boolean isForwardLandingUrl(String currentUrl) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://pub.alimama.com/?forward=")
                || lower.startsWith("http://pub.alimama.com/?forward=");
    }

    private boolean isForwardLandingIncomplete(Browser browser) {
        return isForwardLandingUrl(browser.getCurrentUrl()) || hasQuickEntrance(bodySnippet(browser));
    }

    private boolean hasQuickEntrance(String body) {
        return containsAny(body, QUICK_ENTER_TEXT, QUICK_LOGIN_TEXT);
    }

    private String rowKey(TbPromotionOrderItem row) {
        return String.valueOf(row.getOrderNo()) + "|" + row.getMainOrderNo() + "|" + row.getOrderTime() + "|"
                + row.getEstimatedBillingAmount() + "|" + row.getEstimatedCommission() + "|"
                + row.getActualCommission();
    }

    private YearMonth parseYearMonthText(String text) {
        if (Strings.isEmpty(text)) {
            return null;
        }
        Matcher matcher = MONTH_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private String formatMonth(LocalDate date) {
        return date.getYear() + "年" + date.getMonthValue() + "月";
    }

    private String cellTextAt(Elements cells, int index) {
        if (cells == null || index < 0 || index >= cells.size()) {
            return "";
        }
        return normalizeText(cells.get(index).text());
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private List<String> textLines(Element element) {
        List<String> lines = new ArrayList<String>();
        String text = element == null ? "" : element.wholeText();
        if (Strings.isEmpty(text) && element != null) {
            text = element.text();
        }
        for (String line : text.split("\\R+")) {
            String value = normalizeText(line);
            if (!Strings.isEmpty(value)) {
                lines.add(value);
            }
        }
        if (element != null) {
            for (Element child : element.select("a,div,p,span")) {
                if (!child.children().isEmpty() && !"a".equalsIgnoreCase(child.tagName())) {
                    continue;
                }
                String value = normalizeText(child.text());
                if (!Strings.isEmpty(value) && !lines.contains(value)) {
                    lines.add(value);
                }
            }
        }
        if (lines.isEmpty() && element != null && !Strings.isEmpty(element.text())) {
            lines.add(normalizeText(element.text()));
        }
        return lines;
    }

    private String firstMoney(String text) {
        Matcher matcher = MONEY_PATTERN.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return "";
        }
        return formatMoney(new BigDecimal(matcher.group(1)));
    }

    private String sumMoney(String text, boolean nullWhenDash) {
        String value = text == null ? "" : text;
        Matcher matcher = MONEY_PATTERN.matcher(value);
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        while (matcher.find()) {
            sum = sum.add(new BigDecimal(matcher.group(1)));
            count++;
        }
        if (count == 0) {
            matcher = ANY_NUMBER_PATTERN.matcher(value);
            while (matcher.find()) {
                sum = sum.add(new BigDecimal(matcher.group()));
                count++;
            }
        }
        if (count == 0 && nullWhenDash && value.contains("--")) {
            return null;
        }
        return count == 0 ? "" : formatMoney(sum);
    }

    private String formatMoney(BigDecimal value) {
        return "￥" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String firstLabeled(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text == null ? "" : text);
        return matcher.find() ? normalizeText(matcher.group(1)) : "";
    }

    private String firstMatch(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group() : "";
    }

    private List<String> allMatches(String text, Pattern pattern) {
        List<String> values = new ArrayList<String>();
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private void fail(TbPromotionOrdersResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message == null ? "" : message);
    }

    private String bodySnippet(Browser browser) {
        String body = "";
        for (int i = 0; i < 3; i++) {
            try {
                body = browser.executeScript("return document.body ? document.body.innerText : '';");
                break;
            } catch (Exception e) {
                String message = e.getMessage();
                if (message != null && (message.contains("Execution context was destroyed")
                        || message.contains("because of a navigation"))) {
                    log.debug("Read TB promotion body while navigating, retry={}", i + 1);
                    Extends.sleep(500L * (i + 1));
                    continue;
                }
                log.warn("Read TB promotion body fail, currentUrl={}, error={}", browser.getCurrentUrl(), message);
                return "";
            }
        }
        if (Strings.isEmpty(body) || body.length() <= 2000) {
            return body;
        }
        return body.substring(0, 2000);
    }

    private boolean containsAny(String value, String... keys) {
        if (Strings.isEmpty(value)) {
            return false;
        }
        for (String key : keys) {
            if (value.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDebugEnabled(TbPromotionOrdersRequest request) {
        if (request != null && request.getDebugEnabled() != null) {
            return request.getDebugEnabled();
        }
        return appConfig.getCustom().isDebugEnabled();
    }

    private String resolveDebugOutputDir(TbPromotionOrdersRequest request, TbPromotionConfig config) {
        if (request != null && !Strings.isEmpty(request.getDebugOutputDir())) {
            return request.getDebugOutputDir();
        }
        return config == null ? null : config.getDebugOutputDir();
    }

    private void ensureTaskDeadline(String stage) throws TimeoutException {
        Long deadline = taskDeadlineHolder.get();
        if (deadline != null && System.currentTimeMillis() > deadline) {
            throw new TimeoutException("TB promotion task timeout at " + stage);
        }
    }

    private final class DebugRecorder {
        private final boolean enabled;
        private final Path taskDir;
        private int stepIndex;

        private DebugRecorder(TbPromotionOrdersRequest request, TbPromotionConfig config) {
            this.enabled = isDebugEnabled(request);
            if (!enabled) {
                this.taskDir = null;
                return;
            }
            String baseDir = resolveDebugOutputDir(request, config);
            String profileName = Strings.isEmpty(request.getProfileName()) ? "common" : request.getProfileName();
            String range = request.getStartTime() + "-" + request.getEndTime();
            String time = LocalDateTime.now().format(DEBUG_TIME_FORMATTER);
            this.taskDir = Paths.get(baseDir, profileName, range + "-" + time);
            try {
                Files.createDirectories(this.taskDir);
                Files.writeString(this.taskDir.resolve("_task.txt"),
                        "taskType=" + TASK_TYPE + System.lineSeparator()
                                + "profileName=" + profileName + System.lineSeparator()
                                + "startTime=" + request.getStartTime() + System.lineSeparator()
                                + "endTime=" + request.getEndTime() + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.warn("TB promotion debug directory init fail, path={}, error={}", this.taskDir, e.getMessage(), e);
            }
        }

        private boolean enabled() {
            return enabled;
        }

        private Path getTaskDir() {
            return taskDir;
        }

        private void snapshot(Browser browser, String step) {
            if (!enabled || browser == null || taskDir == null) {
                return;
            }
            String safeStep = step == null ? "step" : step.replaceAll("[^a-zA-Z0-9._-]+", "_");
            int index = ++stepIndex;
            try {
                String html = browser
                        .executeScript("return document.documentElement ? document.documentElement.outerHTML : '';");
                Path file = taskDir.resolve(String.format("%02d_%s.html", index, safeStep));
                Files.writeString(file, html == null ? "" : html, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.warn("TB promotion debug snapshot fail, step={}, error={}", step, e.getMessage(), e);
            }
        }

        private void snapshotText(String step, String text) {
            if (!enabled || taskDir == null) {
                return;
            }
            String safeStep = step == null ? "step" : step.replaceAll("[^a-zA-Z0-9._-]+", "_");
            int index = ++stepIndex;
            String body = text == null ? "" : text;
            try {
                Path file = taskDir.resolve(String.format("%02d_%s.html", index, safeStep));
                Files.writeString(file, "<html><body><pre>" + escapeHtml(body) + "</pre></body></html>",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.warn("TB promotion debug text snapshot fail, step={}, error={}", step, e.getMessage(), e);
            }
        }

        private String escapeHtml(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
