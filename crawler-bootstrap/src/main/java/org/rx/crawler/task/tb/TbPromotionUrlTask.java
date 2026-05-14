package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
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
import org.rx.crawler.task.common.KeepAliveUrlStore;
import org.rx.crawler.task.common.LoginNotificationContext;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.crawler.task.jd.JdUnionProductInfoDto;
import org.rx.exception.InvalidException;
import org.rx.util.BeanMapper;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.tryClose;

@Slf4j
@Service
@RequiredArgsConstructor
public class TbPromotionUrlTask implements CustomCrawlTask<TbPromotionUrlRequest, TbPromotionUrlResult> {
    private static final String TASK_TYPE = "getTbPromotionUrl";
    private static final DateTimeFormatter DEBUG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final String QUICK_ENTER_TEXT = "快速进入";
    private static final String QUICK_LOGIN_TEXT = "快速登录";

    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;
    private final CrawlEntryService entryService;
    private final ResultWriter resultWriter;
    private final ObjectMapper objectMapper;
    private final KeepAliveUrlStore keepAliveUrlStore;
    private final SliderVerifyHandler sliderVerifyHandler = new SliderVerifyHandler();
    private final ThreadLocal<Long> taskDeadlineHolder = new ThreadLocal<Long>();

    public TbPromotionUrlTask(AppConfig appConfig, BrowserProfileManager profileManager, CrawlEntryService entryService,
            ResultWriter resultWriter, ObjectMapper objectMapper) {
        this(appConfig, profileManager, entryService, resultWriter, objectMapper, KeepAliveUrlStore.NOOP);
    }

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public TbPromotionUrlResult execute(TbPromotionUrlRequest request) {
        return getPromotionUrl(request);
    }

    public TbPromotionUrlResult getPromotionUrl(TbPromotionUrlRequest request) {
        return executeInternal(request);
    }

    public boolean closeProfile(String profileName) {
        return profileManager.closeSession(profileName);
    }

    private TbPromotionUrlResult executeInternal(TbPromotionUrlRequest rawRequest) {
        TbPromotionConfig tbConfig = appConfig.getCustom().getTbPromotion();
        TbPromotionUrlRequest request = normalizeRequest(rawRequest, tbConfig);
        TbPromotionUrlResult result = createResult(request);
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

            runPromotionFlow(browser, request, tbConfig, result, debug);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            debug.snapshotText("99-timeout", result.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("TB promotion url fail, productInfo={}, adSiteName={}, profile={}, error={}",
                    request.getProductInfo(), request.getAdSiteName(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            debug.snapshotText("99-failed", result.getMessage());
            return result;
        } finally {
            resultWriter.appendJsonLine(request.getOutputPath(), result);
            tryClose(lease);
            taskDeadlineHolder.remove();
        }
    }

    private CrawlEntryOptions createEntryOptions(TbPromotionUrlRequest request, TbPromotionConfig config) {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setTaskType(TASK_TYPE);
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
                && containsAny(body, "阿里妈妈", "淘宝联盟", "选品广场", "智能搜索", QUICK_ENTER_TEXT, QUICK_LOGIN_TEXT));
        return options;
    }

    private void runPromotionFlow(Browser browser, TbPromotionUrlRequest request, TbPromotionConfig config,
            TbPromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
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

        browser.navigateUrl(config.getPromotionGoodsUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        Extends.sleep(config.nextStepDelayMillis());
        result.setCurrentUrl(browser.getCurrentUrl());
        debug.snapshot(browser, "02-goods-page-loaded");
        if (isLoginRequired(result.getCurrentUrl(), config)) {
            fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                    "TB promotion login expired before entering goods page. Finish login in the opened common Chrome profile, then retry.");
            result.setLoginRequired(true);
            debug.snapshot(browser, "02-goods-login-required");
            return;
        }
        if (!waitGoodsPageReady(browser, config, result, debug)) {
            String message = Boolean.TRUE.equals(result.getDiagnostics().get("02-goods-ready-sliderSliderVerify"))
                    ? "TB promotion slider verify not cleared while entering goods page"
                    : "TB promotion goods page not ready";
            fail(result, CustomCrawlStatus.PAGE_CHANGED, message);
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "02-goods-page-not-ready");
            return;
        }
        if (!checkAndWaitSliderVerify(browser, config, result, debug, "02-goods-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared before search");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "02-goods-slider-not-cleared");
            return;
        }
        debug.snapshot(browser, "02-goods-page-ready");

        if (!nativeSetSearchValue(browser, request.getProductInfo())) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion search input not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "03-search-input-missing");
            return;
        }
        debug.snapshot(browser, "03-search-input-ready");
        if (!checkAndWaitSliderVerify(browser, config, result, debug, "03-search-input-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared after search input");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "03-search-input-slider-not-cleared");
            return;
        }
        if (!nativeClickSmartSearchButton(browser)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion smart search button not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "04-smart-search-missing");
            return;
        }
        Extends.sleep(config.nextStepDelayMillis() * 2L);
        debug.snapshot(browser, "04-smart-search-clicked");
        if (!checkAndWaitSliderVerify(browser, config, result, debug, "04-search-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared after search");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "04-search-slider-not-cleared");
            return;
        }
        if (!waitSearchSubmitted(browser, config, result, debug, request.getProductInfo(), 8)) {
            navigateGoodsSearch(browser, request.getProductInfo(), config);
            debug.snapshot(browser, "04-search-url-fallback");
            if (!checkAndWaitSliderVerify(browser, config, result, debug, "04-search-url-slider")) {
                fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared after search fallback");
                result.getDiagnostics().put("body", bodySnippet(browser));
                debug.snapshot(browser, "04-search-url-slider-not-cleared");
                return;
            }
        }

        waitSearchSettled(browser, config, result, debug, request.getProductInfo());
        scrollToFirstProductCard(browser, config);
        if (!checkAndWaitSliderVerify(browser, config, result, debug, "05-before-card-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared before product card");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "05-before-card-slider-not-cleared");
            return;
        }
        if (isNoProductResult(browser)) {
            fail(result, CustomCrawlStatus.NOT_FOUND, "TB promotion product not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "05-search-no-result");
            return;
        }
        if (!hoverFirstProductCard(browser, config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion product card not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "05-product-card-missing");
            return;
        }
        Extends.sleep(config.nextStepDelayMillis());
        debug.snapshot(browser, "05-product-card-hovered");

        JdUnionProductInfoDto productInfo = readProductInfo(browser);
        if (productInfo != null) {
            result.setProductInfo(productInfo);
        } else {
            result.getDiagnostics().put("productInfoMissing", true);
        }
        debug.snapshot(browser, "06-product-info-collected");

        if (!checkAndWaitSliderVerify(browser, config, result, debug, "07-before-promote-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared before promote click");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "07-before-promote-slider-not-cleared");
            return;
        }
        if (!nativeClickFirstPromoteButton(browser, config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion first promote button not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "07-promote-button-missing");
            return;
        }
        Extends.sleep(config.nextStepDelayMillis() * 2L);
        debug.snapshot(browser, "07-promote-button-clicked");
        if (!checkAndWaitSliderVerify(browser, config, result, debug, "07-promote-click-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared after promote click");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "07-promote-click-slider-not-cleared");
            return;
        }
        if (!waitPromotionDialogReady(browser, config, result, debug)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion material dialog not ready");
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "08-dialog-not-ready");
            return;
        }
        debug.snapshot(browser, "08-dialog-ready");

        if (!nativeClickPromotionSlotTrigger(browser)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slot trigger not found");
            result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
            debug.snapshot(browser, "09-slot-trigger-missing");
            return;
        }
        Extends.sleep(config.nextStepDelayMillis());
        debug.snapshot(browser, "09-slot-popup-opened");

        if (!checkAndWaitSliderVerify(browser, config, result, debug, "10-before-media-type-slider")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slider verify not cleared before media type");
            result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
            debug.snapshot(browser, "10-before-media-type-slider-not-cleared");
            return;
        }
        if (!selectMediaType(browser, config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion media type option not found");
            result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
            debug.snapshot(browser, "10-media-type-missing");
            return;
        }
        Extends.sleep(config.nextStepDelayMillis());
        debug.snapshot(browser, "10-media-type-selected");

        if (!selectMediaNameIfNeeded(browser, request.getMediaName(), config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion media name option not found");
            result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
            debug.snapshot(browser, "11-media-name-missing");
            return;
        }
        debug.snapshot(browser, "11-media-name-selected");

        if (!nativeClickAdSiteDropdown(browser)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion ad site dropdown not found");
            result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
            debug.snapshot(browser, "12-adsite-dropdown-missing");
            return;
        }
        Extends.sleep(config.nextStepDelayMillis());
        debug.snapshot(browser, "12-adsite-dropdown-opened");
        if (!nativeClickAdSiteOption(browser, request.getAdSiteName())) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion ad site option not found");
            result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
            debug.snapshot(browser, "13-adsite-option-missing");
            return;
        }
        Extends.sleep(config.nextStepDelayMillis());
        debug.snapshot(browser, "13-adsite-option-selected");
        if (!nativeClickDialogButton(browser, "确认")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion slot confirm button not found");
            result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
            debug.snapshot(browser, "14-slot-confirm-missing");
            return;
        }
        Extends.sleep(config.nextStepDelayMillis() * 2L);
        debug.snapshot(browser, "14-slot-confirmed");

        String promotionUrl = waitPromotionUrl(browser, config, result, debug, 8);
        nativeClickCopyButton(browser);
        String copiedUrl = waitPromotionUrl(browser, config, result, debug, 3);
        if (Strings.isEmpty(promotionUrl)) {
            promotionUrl = copiedUrl;
        }
        if (Strings.isEmpty(promotionUrl)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "TB promotion url not found");
            result.getDiagnostics().put("dialog", collectDialogDiagnostics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            debug.snapshot(browser, "15-promotion-url-missing");
            return;
        }

        result.setPromotionUrl(promotionUrl);
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("");
        try {
            browser.saveCookies(false);
        } catch (Exception e) {
            log.warn("save TB promotion cookies fail, error={}", e.getMessage());
        }
        keepAliveUrlStore.collect("tb", browser, result.getDiagnostics());
        debug.snapshot(browser, "15-promotion-url-ready");
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
            ensureTaskDeadline("getTbPromotionUrl.completeForwardLanding");
            currentUrl = browser.getCurrentUrl();
            body = bodySnippet(browser);
            boolean forwardLanding = isForwardLandingUrl(currentUrl);
            boolean hasQuickEntrance = hasQuickEntrance(body);
            if (!forwardLanding && !hasQuickEntrance && isLoggedInUrl(currentUrl, config)) {
                return true;
            }
            if (!forwardLanding && !hasQuickEntrance && containsAny(body, "阿里妈妈", "淘宝联盟", "选品广场", "智能搜索")) {
                return true;
            }
            if (hasQuickEntrance
                    && System.currentTimeMillis() - lastClickAt >= Math.max(3000L, config.nextStepDelayMillis() * 3L)
                    && nativeClickQuickEnter(browser)) {
                clicked = true;
                lastClickAt = System.currentTimeMillis();
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
            ensureTaskDeadline("getTbPromotionUrl.waitForwardLandingResolvedAfterClick");
            Extends.sleep(Math.max(700L, config.nextStepDelayMillis()));
            String currentUrl = browser.getCurrentUrl();
            String body = bodySnippet(browser);
            boolean forwardLanding = isForwardLandingUrl(currentUrl);
            boolean hasQuickEntrance = hasQuickEntrance(body);
            if (!forwardLanding && !hasQuickEntrance && isLoggedInUrl(currentUrl, config)) {
                return true;
            }
            if (!forwardLanding && !hasQuickEntrance && containsAny(body, "阿里妈妈", "淘宝联盟", "选品广场", "智能搜索")) {
                return true;
            }
            if (hasQuickEntrance) {
                return false;
            }
        }
        return false;
    }

    private boolean nativeClickQuickEnter(Browser browser) {
        return nativeClickByText(browser, QUICK_ENTER_TEXT, false) || nativeClickByText(browser, QUICK_LOGIN_TEXT, false);
    }

    private String readForwardUrl(Browser browser) {
        String url = browser.executeScript("try{var u=new URL(location.href),f=u.searchParams.get('forward');return f?decodeURIComponent(f):'';}catch(e){return '';}");
        return url == null ? "" : url;
    }

    private boolean waitGoodsPageReady(Browser browser, TbPromotionConfig config,
            TbPromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getInitialPageTimeoutSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitGoodsPageReady");
            Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
            if (isSliderVerifyPage(browser)
                    && !checkAndWaitSliderVerify(browser, config, result, debug, "02-goods-ready-slider")) {
                return false;
            }
            String body = bodySnippet(browser);
            if (containsAny(body, "请输入你要搜索的商品/类目/商品链接", "智能搜索", "选品广场", "批量选品")) {
                return true;
            }
        }
        return false;
    }

    private boolean checkAndWaitSliderVerify(Browser browser, TbPromotionConfig config,
            TbPromotionUrlResult result, DebugRecorder debug, String stepTag) throws TimeoutException {
        if (!isSliderVerifyPage(browser)) {
            return true;
        }
        result.getDiagnostics().put(stepTag + "SliderVerify", true);
        result.getDiagnostics().put(stepTag + "SliderVerifyManualFallback", true);
        debug.snapshot(browser, stepTag + "-manual-wait");
        if (handleSliderVerify(browser, config, result, debug, stepTag)) {
            return true;
        }
        return waitSliderVerifyCleared(browser, config, stepTag);
    }

    private boolean handleSliderVerify(Browser browser, TbPromotionConfig config,
            TbPromotionUrlResult result, DebugRecorder debug, String stepTag) throws TimeoutException {
        boolean handled = onSliderVerifyDetected(browser, config, result, debug, stepTag);
        result.getDiagnostics().put(stepTag + "SliderVerifyAction", handled ? "hook" : "manual");
        return handled;
    }

    /**
     * 滑动验证处理钩子。优先委托 SliderVerifyHandler 自动处理，失败后进入人工兜底等待。
     */
    private boolean onSliderVerifyDetected(Browser browser, TbPromotionConfig config,
            TbPromotionUrlResult result, DebugRecorder debug, String stepTag) throws TimeoutException {
        ensureTaskDeadline("getTbPromotionUrl.onSliderVerifyDetected." + stepTag);
        result.getDiagnostics().put("sliderVerifyAt", stepTag);
        boolean passed = sliderVerifyHandler.checkAndHandle(browser, stepTag, 3,
                config.nextStepDelayMillis(), (b, name) -> debug.snapshot(b, name));
        if (passed) {
            result.getDiagnostics().put("sliderVerifyPassed", true);
            Extends.sleep(config.nextStepDelayMillis() * 2L);
        }
        return passed;
    }

    private boolean waitSliderVerifyCleared(Browser browser, TbPromotionConfig config, String stepTag)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(30, config.getLoginWaitSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitSliderVerifyCleared." + stepTag);
            Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
            if (!isSliderVerifyPage(browser)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSliderVerifyPage(Browser browser) {
        String currentUrl = browser.getCurrentUrl();
        if (containsAny(currentUrl, "/punish", "x5sec", "_____tmd_____", "captcha")) {
            return true;
        }
        Boolean ok = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var body=norm(document.body?(document.body.innerText||document.body.textContent||''):'');" +
                "var words=['请拖动下方滑块完成验证','拖动滑块','拖到最右边','按住滑块','滑块验证','安全验证','访问验证','行为验证','请完成验证','请按住滑块'];" +
                "for(var i=0;i<words.length;i++){if(body.indexOf(words[i])>=0){return true;}}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('iframe,div,span,input,button'));" +
                "for(var j=0;j<nodes.length;j++){var e=nodes[j];var meta=[e.id,e.className,e.name,e.src,e.getAttribute('title'),e.getAttribute('aria-label'),e.getAttribute('data-spm')].join(' ');" +
                "if(!/(nc_|nc-|awsc|captcha|punish|baxia|滑块|验证码|安全验证|x5sec)/i.test(meta)){continue;}" +
                "if(e.tagName==='IFRAME'||visible(e)){return true;}}" +
                "return false;");
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeSetSearchValue(Browser browser, String value) {
        String selector = "[data-rx-tb-promo-search-input='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-promo-search-input';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function meta(el){var v=(el.getAttribute('placeholder')||'')+(el.getAttribute('aria-label')||'')+(el.name||'')+(el.id||'')+(el.className||'');return v;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var chosen=null;for(var i=0;i<nodes.length;i++){var e=nodes[i],m=meta(e);if(visible(e)&&(m.indexOf('请输入你要搜索的商品')>=0||m.indexOf('商品/类目/商品链接')>=0)){chosen=e;break;}}" +
                "if(!chosen){for(var j=0;j<nodes.length;j++){var n=nodes[j],m2=meta(n);if(visible(n)&&(/商品|类目|链接|搜索/.test(m2))){chosen=n;break;}}}" +
                "if(!chosen){return false;}chosen.setAttribute(attr,'1');chosen.scrollIntoView({block:'center',inline:'center'});chosen.focus();return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return setSearchValueByScript(browser, value);
        }
        try {
            browser.elementPress(selector, value, false);
            Extends.sleep(300L);
            if (searchValueEquals(browser, value)) {
                return true;
            }
            return setSearchValueByScript(browser, value) && searchValueEquals(browser, value);
        } catch (Exception e) {
            return setSearchValueByScript(browser, value) && searchValueEquals(browser, value);
        }
    }

    private boolean setSearchValueByScript(Browser browser, String value) {
        Boolean ok = browser.executeScript("var value=arguments[0];" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var chosen=null;for(var i=0;i<nodes.length;i++){var e=nodes[i],m=(e.getAttribute('placeholder')||'')+(e.getAttribute('aria-label')||'');if(visible(e)&&(m.indexOf('请输入你要搜索的商品')>=0||m.indexOf('商品/类目/商品链接')>=0)){chosen=e;break;}}" +
                "if(!chosen){return false;}chosen.focus();" +
                "var setter=Object.getOwnPropertyDescriptor(chosen.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set;" +
                "setter.call(chosen,value);chosen.dispatchEvent(new Event('input',{bubbles:true}));chosen.dispatchEvent(new Event('change',{bubbles:true}));return true;", value);
        return Boolean.TRUE.equals(ok);
    }

    private boolean searchValueEquals(Browser browser, String value) {
        Boolean ok = browser.executeScript("var value=String(arguments[0]||'');" +
                "var input=document.querySelector('[data-rx-tb-promo-search-input=\"1\"]');" +
                "return !!input && String(input.value||'')===value;", value);
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickSmartSearchButton(Browser browser) {
        return nativeClickByText(browser, "智能搜索", true);
    }

    private boolean waitSearchSubmitted(Browser browser, TbPromotionConfig config,
            TbPromotionUrlResult result, DebugRecorder debug, String productInfo, int maxSeconds)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitSearchSubmitted");
            Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
            if (!checkAndWaitSliderVerify(browser, config, result, debug, "04-search-submit-slider")) {
                return false;
            }
            if (isSearchUrl(browser.getCurrentUrl()) || firstProductMatchesQuery(browser, productInfo)) {
                return true;
            }
        }
        return false;
    }

    private void navigateGoodsSearch(Browser browser, String productInfo, TbPromotionConfig config) throws TimeoutException {
        String baseUrl = config.getPromotionGoodsUrl();
        int queryIndex = baseUrl.indexOf('?');
        if (queryIndex >= 0) {
            baseUrl = baseUrl.substring(0, queryIndex);
        }
        String q = URLEncoder.encode(productInfo, StandardCharsets.UTF_8);
        String url = baseUrl + "?pageNum=1&pageSize=30&filters=%7B%7D&fn=search&q="
                + q + "&sort=default&selected=%7B%7D";
        browser.navigateUrl(url, Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
    }

    private void waitSearchSettled(Browser browser, TbPromotionConfig config,
            TbPromotionUrlResult result, DebugRecorder debug, String productInfo) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getPageTimeoutSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitSearchSettled");
            Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
            if (!checkAndWaitSliderVerify(browser, config, result, debug, "04-search-settle-slider")) {
                return;
            }
            String body = bodySnippet(browser);
            if (containsAny(body, "没有商品", "暂无商品", "暂无数据", "无搜索结果", "没有找到")) {
                return;
            }
            if ((isSearchUrl(browser.getCurrentUrl()) || firstProductMatchesQuery(browser, productInfo))
                    && containsAny(body, "立即推广", "当前1-", "共")) {
                return;
            }
        }
    }

    private boolean isSearchUrl(String currentUrl) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return lower.contains("fn=search") || lower.contains("&q=") || lower.contains("?q=");
    }

    private boolean firstProductMatchesQuery(Browser browser, String productInfo) {
        String title = readFirstProductTitle(browser);
        return productTitleMatchesQuery(title, productInfo);
    }

    private String readFirstProductTitle(Browser browser) {
        String title = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var roots=Array.prototype.slice.call(document.querySelectorAll('[data-spm=\"GoodsListItem\"],.GoodsListPlus__CardList-sc-1i2w9r2-0>div,div,li'));" +
                "for(var i=0;i<roots.length;i++){var card=roots[i];if(!visible(card)||card.querySelectorAll('img').length===0){continue;}" +
                "var t=norm(card.innerText||card.textContent);if(!((t.indexOf('佣金率')>=0||t.indexOf('佣金')>=0)&&t.indexOf('到手价')>=0)){continue;}" +
                "var a=card.querySelector('a[data-spm-click*=\"title\"],[class*=\"title-wrap\"], [class*=\"title\"]');" +
                "return norm(a?(a.innerText||a.textContent):t).substring(0,160);}" +
                "return '';");
        return title == null ? "" : title;
    }

    private boolean productTitleMatchesQuery(String title, String productInfo) {
        String t = normalizeSearchText(title);
        String q = normalizeSearchText(productInfo);
        if (Strings.isEmpty(t) || Strings.isEmpty(q)) {
            return false;
        }
        if (t.contains(q) || q.contains(t)) {
            return true;
        }
        int max = Math.min(6, q.length());
        for (int len = max; len >= 2; len--) {
            if (t.contains(q.substring(0, len))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSearchText(String value) {
        if (Strings.isEmpty(value)) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。；：、【】（）()\\[\\]{}<>《》\"'“”‘’]+", "");
    }

    private void scrollToFirstProductCard(Browser browser, TbPromotionConfig config) {
        browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var marker=null,nodes=Array.prototype.slice.call(document.querySelectorAll('div,li,section,article'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],t=norm(e.innerText);if(!visible(e)||e.querySelectorAll('img').length===0){continue;}" +
                "if((t.indexOf('佣金率')>=0||t.indexOf('佣金')>=0)&&t.indexOf('到手价')>=0){marker=e;break;}}" +
                "if(marker){marker.scrollIntoView({block:'center',inline:'center'});window.scrollBy(0,120);}else{window.scrollBy(0,Math.floor(window.innerHeight*0.7));}");
        Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
    }

    private boolean isNoProductResult(Browser browser) {
        String body = bodySnippet(browser);
        if (containsAny(body, "立即推广")) {
            return false;
        }
        return containsAny(body, "没有商品", "暂无商品", "暂无数据", "没有找到", "无搜索结果");
    }

    private boolean hoverFirstProductCard(Browser browser, TbPromotionConfig config) throws TimeoutException {
        for (int i = 0; i < 3; i++) {
            ensureTaskDeadline("getTbPromotionUrl.hoverFirstProductCard");
            Map<String, Object> point = markFirstProductCard(browser);
            if (point != null && !point.isEmpty()) {
                browser.mouseMove(toDouble(point.get("x")), toDouble(point.get("y")));
                if (point.get("actionX") != null && point.get("actionY") != null) {
                    Extends.sleep(260L);
                    browser.mouseMove(toDouble(point.get("actionX")), toDouble(point.get("actionY")));
                }
                return true;
            }
            browser.executeScript("window.scrollBy(0, Math.floor(window.innerHeight * 0.5));");
            Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
        }
        return false;
    }

    private Map<String, Object> markFirstProductCard(Browser browser) {
        return browser.executeScript("var cardAttr='data-rx-tb-promo-card',btnAttr='data-rx-tb-promo-button';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+cardAttr+'],['+btnAttr+']')).forEach(function(e){e.removeAttribute(cardAttr);e.removeAttribute(btnAttr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function scoreCard(el){var r=el.getBoundingClientRect(),t=text(el);if(!visible(el)||r.width<220||r.width>760||r.height<180||r.height>560||el.querySelectorAll('img').length===0){return 999999999;}" +
                "if(!((t.indexOf('佣金率')>=0||t.indexOf('佣金')>=0)&&t.indexOf('到手价')>=0)){return 999999999;}" +
                "return Math.max(0,r.top)*10000+Math.max(0,r.left)+r.width*r.height/100000;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('div,li,section,article')),card=null,best=999999999;" +
                "for(var i=0;i<nodes.length;i++){var sc=scoreCard(nodes[i]);if(sc<best){best=sc;card=nodes[i];}}" +
                "if(!card){return null;}card.setAttribute(cardAttr,'1');card.scrollIntoView({block:'center',inline:'center'});" +
                "var buttons=Array.prototype.slice.call(card.querySelectorAll('button,a,[role=\"button\"],span,div'));" +
                "for(var j=0;j<buttons.length;j++){var b=buttons[j];if(visible(b)&&text(b)==='立即推广'){(b.closest('button,a,[role=\"button\"]')||b).setAttribute(btnAttr,'1');break;}}" +
                "var r=card.getBoundingClientRect();" +
                "return {x:r.left+Math.min(r.width*0.55, Math.max(80, r.width-80)),y:r.top+Math.min(r.height*0.45, Math.max(60, r.height-70))," +
                "actionX:r.left+Math.max(80, r.width-80),actionY:r.top+Math.max(60, r.height-36)};");
    }

    private boolean nativeClickFirstPromoteButton(Browser browser, TbPromotionConfig config) {
        for (int i = 0; i < 2; i++) {
            if (!markFirstPromoteButton(browser)) {
                Map<String, Object> cardPoint = markFirstProductCard(browser);
                if (cardPoint != null && cardPoint.get("actionX") != null && cardPoint.get("actionY") != null) {
                    browser.mouseMove(toDouble(cardPoint.get("actionX")), toDouble(cardPoint.get("actionY")));
                    Extends.sleep(300L);
                }
                continue;
            }
            Map<String, Object> point = markedElementPoint(browser, "[data-rx-tb-promo-button='1']");
            if (point != null) {
                browser.mouseMove(toDouble(point.get("x")), toDouble(point.get("y")));
                Extends.sleep(260L);
            }
            if (nativeClickMarkedElement(browser, "[data-rx-tb-promo-button='1']")) {
                Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
                if (isPromotionDialogVisible(browser) || isSliderVerifyPage(browser)) {
                    return true;
                }
                if (nativeMouseClickMarkedElement(browser, "[data-rx-tb-promo-button='1']")) {
                    Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
                    if (isPromotionDialogVisible(browser) || isSliderVerifyPage(browser)) {
                        return true;
                    }
                }
                if (jsClickMarkedElement(browser, "[data-rx-tb-promo-button='1']")) {
                    Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
                }
                return true;
            }
        }
        return false;
    }

    private boolean isPromotionDialogVisible(Browser browser) {
        return pageContainsAny(browser, "社群素材", "朋友圈素材", "文案素材", "推广位", "一键复制");
    }

    private boolean markFirstPromoteButton(Browser browser) {
        Boolean marked = browser.executeScript("var attr='data-rx-tb-promo-button';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function goodCard(el){for(var p=el;p&&p!==document.body;p=p.parentElement){var t=text(p);if((t.indexOf('佣金率')>=0||t.indexOf('佣金')>=0)&&t.indexOf('到手价')>=0&&p.querySelectorAll('img').length>0){return true;}}return false;}" +
                "function mark(el){var target=el.closest('button,a,[role=\"button\"]')||el;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;}" +
                "var scoped=document.querySelector('[data-rx-tb-promo-card=\"1\"]'),roots=scoped?[scoped]:[document.body];" +
                "if(scoped){var primary=scoped.querySelector('.good-card-promotion-btn,[data-spm-click*=\"d_good_detail_promo\"]');if(primary&&mark(primary)){return true;}}" +
                "for(var r=0;r<roots.length;r++){var nodes=Array.prototype.slice.call(roots[r].querySelectorAll('button,a,[role=\"button\"],span,div'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],t=text(e),cls=String(e.className||''),spm=String(e.getAttribute('data-spm-click')||'');" +
                "if(!visible(e)||!goodCard(e)){continue;}if(t==='立即推广'||cls.indexOf('good-card-promotion-btn')>=0||spm.indexOf('d_good_detail_promo')>=0){return mark(e);}}}" +
                "return false;");
        return Boolean.TRUE.equals(marked);
    }

    private JdUnionProductInfoDto readProductInfo(Browser browser) {
        Map<String, Object> raw = browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function rawText(el){return norm((el.innerText||'')+' '+(el.textContent||''));}" +
                "function pickImage(card){var imgs=Array.prototype.slice.call(card.querySelectorAll('img'));for(var i=0;i<imgs.length;i++){var img=imgs[i];if(!visible(img)){continue;}return img.currentSrc||img.src||img.getAttribute('data-src')||img.getAttribute('src')||'';}return '';}" +
                "function extract(t,re){var m=t.match(re);return m?(m[1]||m[0]):'';}" +
                "function normalizeUrl(url){if(!url){return '';}if(url.indexOf('//')===0){return location.protocol+url;}return url;}" +
                "function hrefOf(a){return a?(a.getAttribute('href')||a.href||''):'';}" +
                "function pickTitleLink(card){var selectors=[" +
                "'a[href][data-spm-click*=\\\"d_good_select_list_title_click\\\"]'," +
                "'a[href] .union-good-card-block-title-wrap'," +
                "'a[href] [class*=\\\"union-good-card-block-title\\\"]'," +
                "'a[href] [class*=\\\"title-wrap\\\"]'," +
                "'a[href] [class*=\\\"title\\\"]'];" +
                "for(var s=0;s<selectors.length;s++){var node=card.querySelector(selectors[s]);if(!node){continue;}" +
                "var a=node.matches&&node.matches('a[href]')?node:node.closest('a[href]');if(!a){continue;}" +
                "var href=hrefOf(a);if(!href||!/portal\\/v2\\/pages\\/promo\\/goods\\/detail/.test(href)){continue;}" +
                "var titleNode=a.querySelector('.union-good-card-block-title,[class*=\\\"title\\\"]')||a;" +
                "return {name:text(titleNode)||text(a),link:href};}return null;}" +
                "var card=document.querySelector('[data-rx-tb-promo-card=\"1\"]');if(!card){return null;}var full=rawText(card);" +
                "var titleLink=pickTitleLink(card),name=titleLink?titleLink.name:'',link=titleLink?titleLink.link:'',best=-1;" +
                "var anchors=Array.prototype.slice.call(card.querySelectorAll('a[href]'));" +
                "if(!link){for(var i=0;i<anchors.length;i++){var a=anchors[i],t=text(a),href=hrefOf(a),cls=String(a.className||''),spm=String(a.getAttribute('data-spm-click')||'');" +
                "if(!href||!/\\S/.test(href)){continue;}if(/立即推广|更多信息|加入收藏|创建淘礼金|回头客|全网爆款|官方立减|券/.test(t)&&t.length<=40){continue;}" +
                "if(/shop\\/detail|third\\/manage|customer|help|logout/i.test(href)){continue;}" +
                "var titleLike=spm.indexOf('title')>=0||cls.indexOf('title')>=0||!!a.querySelector('[class*=\"title\"]');" +
                "var score=0;if(visible(a)){score+=100;}if(t){score+=Math.min(120,t.length);}if(titleLike){score+=260;}" +
                "if(/portal\\/v2\\/pages\\/promo\\/goods\\/detail|itemId=|outputMktId=/.test(href)){score+=180;}" +
                "if(/item\\.taobao\\.com|detail\\.tmall\\.com|market\\.m\\.taobao\\.com/.test(href)){score+=220;}" +
                "if(!t&&score<180){continue;}if(score>best){best=score;name=t;link=href;}}}" +
                "if(!link){var title=card.querySelector('[class*=\"title-wrap\"],[class*=\"title\"]');if(title){name=text(title);var fallbackTitleLink=title.closest('a[href]');if(fallbackTitleLink){link=hrefOf(fallbackTitleLink);}}}" +
                "var store=extract(full,/店铺名称[:：]\\s*([^\\n\\r]+?)(?:类目[:：]|2小时推广销量|月推广销量|券量|更多信息|$)/);" +
                "if(!store){var ns=Array.prototype.slice.call(card.querySelectorAll('a,span,div'));for(var j=0;j<ns.length;j++){var st=text(ns[j]);if(visible(ns[j])&&/店$|旗舰店|专卖店|店铺/.test(st)&&st.length<=50){store=st;break;}}}" +
                "return {imageUrl:normalizeUrl(pickImage(card)),productName:name,productLink:normalizeUrl(link)," +
                "commissionRate:extract(full,/(?:佣金率|佣金比例)[:：]?\\s*([0-9.]+%)/),finalPrice:extract(full,/到手价\\s*[￥¥]?\\s*([0-9.]+(?:\\.[0-9]+)?)/),storeName:store};");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        JdUnionProductInfoDto dto = objectMapper.convertValue(raw, JdUnionProductInfoDto.class);
        trimProductInfo(dto);
        return dto;
    }

    private void trimProductInfo(JdUnionProductInfoDto dto) {
        if (dto == null) {
            return;
        }
        dto.setImageUrl(trim(dto.getImageUrl()));
        dto.setProductName(trim(dto.getProductName()));
        dto.setProductLink(trim(dto.getProductLink()));
        dto.setCommissionRate(trim(dto.getCommissionRate()));
        dto.setFinalPrice(trim(dto.getFinalPrice()));
        dto.setStoreName(trim(dto.getStoreName()));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean waitPromotionDialogReady(Browser browser, TbPromotionConfig config,
            TbPromotionUrlResult result, DebugRecorder debug) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getPageTimeoutSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitPromotionDialogReady");
            Extends.sleep(Math.max(1000, config.nextStepDelayMillis()));
            if (!checkAndWaitSliderVerify(browser, config, result, debug, "08-dialog-slider")) {
                return false;
            }
            if (pageContainsAny(browser, "社群素材", "朋友圈素材", "文案素材", "推广位", "一键复制")) {
                return true;
            }
        }
        return false;
    }

    private boolean nativeClickPromotionSlotTrigger(Browser browser) {
        String selector = "[data-rx-tb-slot-trigger='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-tb-slot-trigger';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('placeholder')||el.getAttribute('aria-label'));}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p')),label=null;" +
                "for(var i=0;i<labels.length;i++){var e=labels[i],t=text(e);if(visible(e)&&t.indexOf('推广位')>=0&&t.length<=8){label=e;break;}}" +
                "if(!label){return false;}var lr=label.getBoundingClientRect(),nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea,button,a,div,span'));" +
                "var best=null,bestScore=999999;for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n)||n===label||label.contains(n)||n.contains(label)){continue;}" +
                "var r=n.getBoundingClientRect(),dy=Math.abs((r.top+r.bottom-lr.top-lr.bottom)/2),dx=r.left-lr.right,area=r.width*r.height,t=text(n);" +
                "if(dx<-30||dy>70||r.width<80||r.height<22||area>90000||t.indexOf('推广位')>=0){continue;}" +
                "var score=dy*20+Math.max(0,dx)+area/1000;if(score<bestScore){best=n;bestScore=score;}}" +
                "if(!best){return false;}var target=best.closest('button,a,[role=\"button\"],[role=\"combobox\"]')||best;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;");
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

    private boolean selectMediaType(Browser browser, TbPromotionConfig config) {
        if (!nativeClickFormSelect(browser, "siteType", "data-rx-tb-media-type")
                && !nativeClickLabelControl(browser, "媒体类型", "data-rx-tb-media-type")) {
            return false;
        }
        Extends.sleep(config.nextStepDelayMillis());
        if (!nativeClickOptionByText(browser, "他方平台", false)) {
            if (!nativeClickFormSelect(browser, "siteType", "data-rx-tb-media-type")
                    && !nativeClickLabelControl(browser, "媒体类型", "data-rx-tb-media-type")) {
                return false;
            }
            Extends.sleep(config.nextStepDelayMillis());
            if (!nativeClickOptionByText(browser, "他方平台", false)) {
                return false;
            }
        }
        Extends.sleep(config.nextStepDelayMillis());
        return nativeClickOptionByText(browser, "社交平台", false);
    }

    private boolean selectMediaNameIfNeeded(Browser browser, String mediaName, TbPromotionConfig config) {
        if (Strings.isEmpty(mediaName)) {
            return true;
        }
        if (selectedLabelControlEquals(browser, "媒体名称", mediaName)) {
            return true;
        }
        if (!nativeClickFormSelect(browser, "siteId", "data-rx-tb-media-name")
                && !nativeClickLabelControl(browser, "媒体名称", "data-rx-tb-media-name")) {
            return false;
        }
        Extends.sleep(config.nextStepDelayMillis());
        return nativeClickOptionByText(browser, mediaName, true);
    }

    private boolean nativeClickAdSiteDropdown(Browser browser) {
        return nativeClickFormSelect(browser, "adzoneId", "data-rx-tb-adsite-name")
                || nativeClickFormSelect(browser, "adzoneName", "data-rx-tb-adsite-name")
                || nativeClickLabelControl(browser, "推广位名称", "data-rx-tb-adsite-name");
    }

    private boolean nativeClickAdSiteOption(Browser browser, String adSiteName) {
        String selector = "[data-rx-tb-adsite-option='1']";
        Boolean marked = browser.executeScript("var target=String(arguments[0]||''),attr='data-rx-tb-adsite-option';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('li,div,span,a,[role=\"option\"]')),best=null,bestScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=text(e),m=t.match(/^\\s*(\\d+)/);if(!m||m[1]!==target){continue;}" +
                "var r=e.getBoundingClientRect(),score=Math.abs(r.top-260)+r.width*r.height/3000;if(score<bestScore){best=e;bestScore=score;}}" +
                "if(!best){return false;}var targetEl=best.closest('li,a,[role=\"option\"]')||best;targetEl.setAttribute(attr,'1');targetEl.scrollIntoView({block:'center',inline:'center'});return true;", adSiteName);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        return nativeClickMarkedElement(browser, selector);
    }

    private boolean nativeClickFormSelect(Browser browser, String fieldId, String attr) {
        String selector = "[" + attr + "='1']";
        Boolean marked = browser.executeScript("var fieldId=String(arguments[0]||''),attr=arguments[1];" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "var input=document.getElementById(fieldId);if(!input||!visible(input)){return false;}" +
                "var target=input.closest('.next-select-trigger,.next-select,[aria-haspopup=true],[role=combobox]')||input;" +
                "if(!visible(target)){target=input;}" +
                "target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;", fieldId, attr);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        return nativeClickMarkedElement(browser, selector);
    }

    private boolean nativeClickLabelControl(Browser browser, String labelText, String attr) {
        String selector = "[" + attr + "='1']";
        Boolean marked = browser.executeScript("var labelText=String(arguments[0]||''),attr=arguments[1];" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('placeholder')||el.getAttribute('aria-label'));}" +
                "var dialogs=Array.prototype.slice.call(document.querySelectorAll('[role=\"dialog\"],.next-dialog')),scope=document;" +
                "for(var d=dialogs.length-1;d>=0;d--){if(visible(dialogs[d])){scope=dialogs[d];break;}}" +
                "var labels=Array.prototype.slice.call(scope.querySelectorAll('label,span,div,p')),label=null;" +
                "for(var i=0;i<labels.length;i++){var e=labels[i],t=text(e);if(visible(e)&&t.indexOf(labelText)>=0&&t.length<=labelText.length+8){label=e;break;}}" +
                "if(!label){return false;}" +
                "if(label.tagName==='LABEL'&&label.getAttribute('for')){var input=document.getElementById(label.getAttribute('for'));if(input&&visible(input)){var targetByFor=input.closest('.next-select-trigger,.next-select,[aria-haspopup=true],[role=combobox]')||input;targetByFor.setAttribute(attr,'1');targetByFor.scrollIntoView({block:'center',inline:'center'});return true;}}" +
                "var lr=label.getBoundingClientRect(),nodes=Array.prototype.slice.call(scope.querySelectorAll('input,textarea,button,a,div,span'));" +
                "var best=null,bestScore=999999;for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n)||n===label||label.contains(n)||n.contains(label)){continue;}" +
                "var r=n.getBoundingClientRect(),dy=Math.abs((r.top+r.bottom-lr.top-lr.bottom)/2),dx=r.left-lr.right,area=r.width*r.height,t=text(n);" +
                "if(dx<-25||dy>55||r.width<70||r.height<22||area>70000||t.indexOf(labelText)>=0||t.indexOf('新增媒体')>=0){continue;}" +
                "var score=dy*20+Math.max(0,dx)+area/1000;if(score<bestScore){best=n;bestScore=score;}}" +
                "if(!best){return false;}var target=best.closest('button,a,[role=\"button\"],[role=\"combobox\"],.next-select-trigger,.next-select')||best;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;", labelText, attr);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        return nativeClickMarkedElement(browser, selector);
    }

    private boolean selectedLabelControlEquals(Browser browser, String labelText, String expected) {
        Boolean ok = browser.executeScript("var labelText=String(arguments[0]||''),expected=String(arguments[1]||'').replace(/\\s+/g,'').trim();" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('placeholder')||el.getAttribute('aria-label'));}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p')),label=null;" +
                "for(var i=0;i<labels.length;i++){var e=labels[i],t=text(e);if(visible(e)&&t.indexOf(labelText)>=0&&t.length<=labelText.length+8){label=e;break;}}" +
                "if(!label){return false;}var lr=label.getBoundingClientRect(),nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea,button,a,div,span'));" +
                "for(var j=0;j<nodes.length;j++){var n=nodes[j];if(!visible(n)||n===label||label.contains(n)||n.contains(label)){continue;}" +
                "var r=n.getBoundingClientRect(),dy=Math.abs((r.top+r.bottom-lr.top-lr.bottom)/2),dx=r.left-lr.right;if(dx<-25||dy>55){continue;}" +
                "if(text(n)===expected){return true;}}" +
                "return false;", labelText, expected);
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickOptionByText(Browser browser, String optionText, boolean exact) {
        String selector = "[data-rx-tb-option='1']";
        Boolean marked = browser.executeScript("var target=String(arguments[0]||'').replace(/\\s+/g,'').trim(),exact=arguments[1],attr='data-rx-tb-option';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('li,div,span,a,[role=\"option\"]')),best=null,bestScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=text(e);if(!t){continue;}var matched=exact?t===target:t.indexOf(target)>=0;if(!matched){continue;}" +
                "var r=e.getBoundingClientRect();if(r.top<0||r.left<0||r.top>window.innerHeight||r.left>window.innerWidth){continue;}" +
                "var inOverlay=e.closest('.next-overlay-wrapper,.next-menu,.next-select-menu,.next-dialog,[role=\"listbox\"]')?0:500;" +
                "var score=inOverlay+(t===target?0:100)+Math.abs(r.top-260)+r.width*r.height/3000;if(score<bestScore){best=e;bestScore=score;}}" +
                "if(!best){return false;}var targetEl=best.closest('li,a,[role=\"option\"]')||best;targetEl.setAttribute(attr,'1');targetEl.scrollIntoView({block:'center',inline:'center'});return true;", optionText, exact);
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        return nativeClickMarkedElement(browser, selector);
    }

    private boolean nativeClickMarkedElement(Browser browser, String selector) {
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception clickError) {
            return nativeMouseClickMarkedElement(browser, selector);
        }
    }

    private boolean nativeMouseClickMarkedElement(Browser browser, String selector) {
        try {
            Map<String, Object> point = markedElementPoint(browser, selector);
            if (point == null || !(point.get("x") instanceof Number) || !(point.get("y") instanceof Number)) {
                return false;
            }
            double x = ((Number) point.get("x")).doubleValue();
            double y = ((Number) point.get("y")).doubleValue();
            browser.mouseClick(x, y);
            return true;
        } catch (Exception moveError) {
            return false;
        }
    }

    private boolean jsClickMarkedElement(Browser browser, String selector) {
        Boolean clicked = browser.executeScript("var el=document.querySelector(String(arguments[0]||''));" +
                "if(!el){return false;}" +
                "var target=el.closest('button,a,[role=\"button\"]')||el;" +
                "target.click();" +
                "return true;", selector);
        return Boolean.TRUE.equals(clicked);
    }

    private Map<String, Object> markedElementPoint(Browser browser, String selector) {
        return browser.executeScript("var el=document.querySelector(String(arguments[0]||''));" +
                "if(!el){return null;}var r=el.getBoundingClientRect();" +
                "return {x:r.left+r.width/2,y:r.top+r.height/2};", selector);
    }

    private boolean nativeClickDialogButton(Browser browser, String text) {
        String selector = "[data-rx-tb-dialog-button='1']";
        Boolean marked = browser.executeScript("var target=String(arguments[0]||'').replace(/\\s+/g,'').trim(),attr='data-rx-tb-dialog-button';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,[role=\"button\"],span,div')),best=null,bestScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=text(e);if(t!==target){continue;}" +
                "var targetEl=e.closest('button,a,[role=\"button\"]')||e;if(!visible(targetEl)){continue;}var tt=text(targetEl);if(tt.indexOf('取消')>=0&&target.indexOf('取消')<0){continue;}" +
                "var r=targetEl.getBoundingClientRect(),score=Math.abs(r.top-window.innerHeight*0.65)+r.width*r.height/1000;if(score<bestScore){best=targetEl;bestScore=score;}}" +
                "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});return true;", text);
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

    private String waitPromotionUrl(Browser browser, TbPromotionConfig config,
            TbPromotionUrlResult result, DebugRecorder debug, int maxSeconds) throws TimeoutException {
        long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        String promotionUrl = "";
        while (System.currentTimeMillis() < deadline) {
            ensureTaskDeadline("getTbPromotionUrl.waitPromotionUrl");
            Extends.sleep(Math.max(800, config.nextStepDelayMillis()));
            if (!checkAndWaitSliderVerify(browser, config, result, debug, "15-promotion-url-slider")) {
                return "";
            }
            promotionUrl = readPromotionUrl(browser);
            if (!Strings.isEmpty(promotionUrl)) {
                return promotionUrl;
            }
        }
        return promotionUrl;
    }

    private boolean nativeClickCopyButton(Browser browser) {
        return nativeClickByText(browser, "一键复制", true);
    }

    private String readPromotionUrl(Browser browser) {
        String url = browser.executeScript("function pick(v){if(!v){return '';}var m=String(v).match(/https?:\\/\\/[^\\s\"'<>，。]+/);return m?m[0]:'';}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function score(url,ctx){var s=0;if(/m\\.tb\\.cn|tb\\.cn|s\\.click\\.taobao\\.com|uland\\.taobao\\.com|taobao\\.com/i.test(url)){s+=1000;}if(/下单链接|淘口令|短链接|长链接|链接/.test(ctx)){s+=100;}return s;}" +
                "var best='',bestScore=-1,nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var u=pick(e.value||e.innerText);if(!u){continue;}var ctx=(e.innerText||e.value||'');for(var p=e.parentElement;p&&p!==document.body;p=p.parentElement){ctx+=' '+(p.innerText||'');if(ctx.length>1000){break;}}" +
                "var sc=score(u,ctx);if(sc>bestScore){best=u;bestScore=sc;}}" +
                "if(best){return best;}" +
                "var body=document.body?document.body.innerText:'';var urls=body.match(/https?:\\/\\/[^\\s\"'<>，。]+/g)||[];" +
                "for(var j=0;j<urls.length;j++){var sc2=score(urls[j],body);if(sc2>bestScore){best=urls[j];bestScore=sc2;}}" +
                "return best||'';");
        return url == null ? "" : url.trim();
    }

    private boolean nativeClickByText(Browser browser, String text, boolean exact) {
        String selector = "[data-rx-tb-click-target='1']";
        Boolean marked = browser.executeScript("var target=String(arguments[0]||'').replace(/\\s+/g,'').trim(),exact=arguments[1],attr='data-rx-tb-click-target';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.textContent||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,[role=\"button\"],li,span,div'));" +
                "var best=null,bestScore=999999;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var t=text(e);if(!t){continue;}" +
                "var matched=exact?t===target:t.indexOf(target)>=0;if(!matched){continue;}var targetEl=e.closest('button,a,[role=\"button\"]')||e;" +
                "var r=targetEl.getBoundingClientRect(),score=(t===target?0:100)+r.width*r.height/1000+Math.abs(r.top-260);if(score<bestScore){best=targetEl;bestScore=score;}}" +
                "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});return true;", text, exact);
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

    private Map<String, Object> collectDialogDiagnostics(Browser browser) {
        return browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function item(el){var r=el.getBoundingClientRect();return {tag:el.tagName,role:el.getAttribute('role')||'',className:norm(el.className).substring(0,120),text:norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('placeholder')).substring(0,120),value:norm(el.value).substring(0,120),top:Math.round(r.top),left:Math.round(r.left),width:Math.round(r.width),height:Math.round(r.height)};}" +
                "return {url:location.href,inputs:Array.prototype.slice.call(document.querySelectorAll('input,textarea')).filter(visible).map(item),buttons:Array.prototype.slice.call(document.querySelectorAll('button,a,[role=\"button\"],[role=\"option\"],li')).filter(visible).map(item),body:norm(document.body?document.body.innerText:'').substring(0,1600)};");
    }

    @SneakyThrows
    private WebBrowserConfig createBrowserConfig(TbPromotionUrlRequest request, TbPromotionConfig tbConfig) {
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

    private TbPromotionUrlRequest normalizeRequest(TbPromotionUrlRequest request, TbPromotionConfig config) {
        if (request == null) {
            request = new TbPromotionUrlRequest();
        }
        if (Strings.isEmpty(request.getProductInfo())) {
            throw new InvalidException("productInfo is required");
        }
        if (Strings.isEmpty(request.getAdSiteName())) {
            throw new InvalidException("adSiteName is required");
        }
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(Strings.isEmpty(config.getProfileName()) ? profileManager.defaultProfileName()
                    : config.getProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        if (Strings.isEmpty(request.getMediaName())) {
            request.setMediaName(config.getDefaultMediaName());
        }
        if (Strings.isEmpty(request.getOutputPath())) {
            request.setOutputPath(config.getDefaultOutputPath());
        }
        return request;
    }

    private TbPromotionUrlResult createResult(TbPromotionUrlRequest request) {
        TbPromotionUrlResult result = new TbPromotionUrlResult();
        result.setTaskType(TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setProductInfoText(request.getProductInfo());
        result.setAdSiteName(request.getAdSiteName());
        result.setMediaName(request.getMediaName());
        result.setProfileName(request.getProfileName());
        return result;
    }

    private void applyEntryResult(TbPromotionUrlResult result, CrawlEntryResult entry) {
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
                || lower.startsWith(config.getPromotionGoodsUrl().toLowerCase(Locale.ROOT))
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

    private void fail(TbPromotionUrlResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message == null ? "" : message);
        if (status == CustomCrawlStatus.LOGIN_REQUIRED) {
            result.setLoginRequired(true);
            notifyLoginRequired(result);
        }
    }

    private void notifyLoginRequired(TbPromotionUrlResult result) {
        if (Boolean.TRUE.equals(result.getDiagnostics().get("loginNotificationAttempted"))) {
            return;
        }
        result.getDiagnostics().put("loginNotificationAttempted", true);
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType(TASK_TYPE);
        context.setProfileName(result.getProfileName());
        context.setInitialUrl(appConfig.getCustom().getTbPromotion().getHomeUrl());
        context.setCurrentUrl(result.getCurrentUrl());
        context.setMessage(result.getMessage());
        context.setLoginWaitSeconds(appConfig.getCustom().getTbPromotion().getLoginWaitSeconds());
        context.setKeepBrowserOpenSeconds(appConfig.getCustom().getTbPromotion().getKeepBrowserOpenSecondsOnLoginRequired());
        entryService.notifyLoginRequired(context);
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
                    log.debug("Read TB promotion url body while navigating, retry={}", i + 1);
                    Extends.sleep(500L * (i + 1));
                    continue;
                }
                log.warn("Read TB promotion url body fail, currentUrl={}, error={}", browser.getCurrentUrl(), message);
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

    private boolean pageContainsAny(Browser browser, String... keys) {
        if (keys == null || keys.length == 0) {
            return false;
        }
        Boolean ok = browser.executeScript("var keys=arguments[0]||[];" +
                "var body=document.body?(document.body.innerText||''):'';" +
                "for(var i=0;i<keys.length;i++){if(body.indexOf(keys[i])>=0){return true;}}" +
                "return false;", java.util.Arrays.asList(keys));
        return Boolean.TRUE.equals(ok);
    }

    private double toDouble(Object value) {
        if (value == null) {
            return 0D;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0D;
        }
    }

    private boolean isDebugEnabled(TbPromotionUrlRequest request) {
        if (request != null && request.getDebugEnabled() != null) {
            return request.getDebugEnabled();
        }
        return appConfig.getCustom().isDebugEnabled();
    }

    private String resolveDebugOutputDir(TbPromotionUrlRequest request, TbPromotionConfig config) {
        if (request != null && !Strings.isEmpty(request.getDebugOutputDir())) {
            return request.getDebugOutputDir();
        }
        return config == null ? null : config.getDebugOutputDir();
    }

    private void ensureTaskDeadline(String stage) throws TimeoutException {
        Long deadline = taskDeadlineHolder.get();
        if (deadline != null && System.currentTimeMillis() > deadline) {
            throw new TimeoutException("TB promotion url task timeout at " + stage);
        }
    }

    private final class DebugRecorder {
        private final boolean enabled;
        private final Path taskDir;
        private int stepIndex;

        private DebugRecorder(TbPromotionUrlRequest request, TbPromotionConfig config) {
            this.enabled = isDebugEnabled(request);
            if (!enabled) {
                this.taskDir = null;
                return;
            }
            String baseDir = resolveDebugOutputDir(request, config);
            String profileName = Strings.isEmpty(request.getProfileName()) ? "common" : request.getProfileName();
            String product = safePathPart(request.getProductInfo());
            String time = LocalDateTime.now().format(DEBUG_TIME_FORMATTER);
            this.taskDir = Paths.get(baseDir, profileName, product + "-" + time);
            try {
                Files.createDirectories(this.taskDir);
                Files.writeString(this.taskDir.resolve("_task.txt"),
                        "taskType=" + TASK_TYPE + System.lineSeparator()
                                + "profileName=" + profileName + System.lineSeparator()
                                + "productInfo=" + request.getProductInfo() + System.lineSeparator()
                                + "adSiteName=" + request.getAdSiteName() + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.warn("TB promotion url debug directory init fail, path={}, error={}", this.taskDir, e.getMessage(), e);
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
                String html = browser.executeScript("return document.documentElement ? document.documentElement.outerHTML : '';");
                Path file = taskDir.resolve(String.format("%02d_%s.html", index, safeStep));
                Files.writeString(file, html == null ? "" : html, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (Exception e) {
                log.warn("TB promotion url debug snapshot fail, step={}, error={}", step, e.getMessage(), e);
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
                log.warn("TB promotion url debug text snapshot fail, step={}, error={}", step, e.getMessage(), e);
            }
        }

        private String safePathPart(String value) {
            String text = Strings.isEmpty(value) ? "unknown" : value.trim();
            text = text.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]+", "_");
            if (text.length() > 40) {
                return text.substring(0, 40);
            }
            return text;
        }

        private String escapeHtml(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
