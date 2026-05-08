package org.rx.crawler.task.jd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.core.Reflects;
import org.rx.core.Strings;
import org.rx.crawler.Browser;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.MemoryCookieContainer;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.crawler.task.common.BrowserProfileManager;
import org.rx.crawler.task.common.CrawlEntryOptions;
import org.rx.crawler.task.common.CrawlEntryResult;
import org.rx.crawler.task.common.CrawlEntryService;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.CustomCrawlTask;
import org.rx.crawler.task.common.ResultWriter;
import org.rx.exception.InvalidException;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RemotingEventArgs;
import org.rx.net.transport.TcpServer;
import org.rx.util.BeanMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.rx.core.Extends.tryClose;

@Slf4j
@Service
@RequiredArgsConstructor
public class JdUnionPromotionTask implements CustomCrawlTask<JdUnionPromotionRequest, JdUnionPromotionResult>, JdUnionCrawlContract {
    private static final String TASK_TYPE = "getPromotionUrl";
    private static final Pattern SEARCH_RESULT_COUNT_PATTERN = Pattern.compile("所有结果\\s*共\\s*(\\d+)\\s*件商品");

    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;
    private final CrawlEntryService entryService;
    private final ResultWriter resultWriter;
    private final ObjectMapper objectMapper;
    private TcpServer remotingServer;

    @PostConstruct
    public void init() {
        if (!appConfig.getCustom().isRemotingEnabled() || appConfig.getCustom().getRemotingListenPort() <= 0) {
            return;
        }
        try {
            remotingServer = Remoting.register(this, appConfig.getCustom().getRemotingListenPort(), false);
            log.info("JD union custom crawl remoting listen on {}", appConfig.getCustom().getRemotingListenPort());
        } catch (Exception e) {
            log.warn("JD union custom crawl remoting register fail, port={}, error={}",
                    appConfig.getCustom().getRemotingListenPort(), e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        tryClose(remotingServer);
    }

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public JdUnionPromotionResult execute(JdUnionPromotionRequest request) {
        return getPromotionUrl(request);
    }

    @Override
    public JdUnionPromotionResult getPromotionUrl(JdUnionPromotionRequest request) {
        JdUnionPromotionResult result = executeInternal(request, true);
        publishDirectResult(result);
        return result;
    }


    @Override
    public JdUnionPromotionResult loginCheck(JdUnionPromotionRequest request) {
        JdUnionPromotionResult result = executeInternal(request, false);
        publishDirectResult(result);
        return result;
    }

    @Override
    public boolean closeProfile(String profileName) {
        return profileManager.closeSession(profileName);
    }

    public List<JdUnionPromotionResult> batch(JdUnionBatchRequest request) {
        List<JdUnionPromotionRequest> items = loadBatchItems(request);
        List<JdUnionPromotionResult> results = new ArrayList<JdUnionPromotionResult>();
        for (JdUnionPromotionRequest item : items) {
            if (Strings.isEmpty(item.getOutputPath()) && !Strings.isEmpty(request.getOutputPath())) {
                item.setOutputPath(request.getOutputPath());
            }
            results.add(getPromotionUrl(item));
        }
        return results;
    }

    private JdUnionPromotionResult executeInternal(JdUnionPromotionRequest rawRequest, boolean doPromotion) {
        JdUnionConfig jdConfig = appConfig.getCustom().getJdUnion();
        JdUnionPromotionRequest request = normalizeRequest(rawRequest, jdConfig);
        JdUnionPromotionResult result = createResult(request);
        BrowserProfileManager.ProfileLease lease = null;
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(request, jdConfig));
            Browser browser = lease.getBrowser();

            CrawlEntryResult entry = entryService.enter(browser, lease, createEntryOptions(request, jdConfig));
            applyEntryResult(result, entry);
            if (!entry.isPassed()) {
                return result;
            }
            if (!doPromotion) {
                result.setStatus(CustomCrawlStatus.SUCCESS);
                result.setMessage("JD Union login state is valid");
                return result;
            }
            runPromotionFlow(browser, request, jdConfig, result);
            return result;
        } catch (TimeoutException e) {
            fail(result, CustomCrawlStatus.TIMEOUT, e.getMessage());
            return result;
        } catch (Exception e) {
            log.warn("JD union promotion fail, skuId={}, profile={}, error={}",
                    request.getSkuId(), request.getProfileName(), e.getMessage(), e);
            fail(result, CustomCrawlStatus.FAILED, e.getMessage());
            return result;
        } finally {
            resultWriter.appendJsonLine(request.getOutputPath(), result);
            tryClose(lease);
        }
    }

    private CrawlEntryOptions createEntryOptions(JdUnionPromotionRequest request, JdUnionConfig config) {
        CrawlEntryOptions options = new CrawlEntryOptions();
        options.setProfileName(request.getProfileName());
        options.setPreflightEnabled(config.isPreflightEnabled());
        options.setPreflightUrl(config.getPreflightUrl());
        options.setPreflightStrict(config.isPreflightStrict());
        options.setPreflightCacheMinutes(config.getPreflightCacheMinutes());
        options.setForcePreflight(request.getForcePreflight() == null ? config.isForcePreflight() : request.getForcePreflight());
        options.setInitialUrl(config.getOverviewUrl());
        options.setLoginUrlPrefix(config.getLoginUrlPrefix());
        options.setInitialPageTimeoutSeconds(config.getInitialPageTimeoutSeconds());
        options.setLoginWaitSeconds(config.getLoginWaitSeconds());
        options.setStepDelayMillis(config.getStepDelayMillis());
        options.setKeepBrowserOpenOnLoginRequired(request.getKeepBrowserOpenOnLoginRequired() == null
                ? config.isKeepBrowserOpenOnLoginRequired() : request.getKeepBrowserOpenOnLoginRequired());
        options.setKeepBrowserOpenSecondsOnLoginRequired(config.getKeepBrowserOpenSecondsOnLoginRequired());
        options.setLoginRequiredUrlMatcher(url -> isLoginRequired(url, config));
        options.setLoggedInUrlMatcher(url -> isLoggedInUrl(url) || isOverviewUrl(url, config));
        options.setLoggedInBodyMatcher(body -> !containsAny(body, "登录", "扫码", "验证码")
                && containsAny(body, "京东联盟", "我要推广", "推广管理", "商品"));
        return options;
    }

    private void applyEntryResult(JdUnionPromotionResult result, CrawlEntryResult entry) {
        result.setFingerprintPassed(entry.isFingerprintPassed());
        result.setCurrentUrl(entry.getCurrentUrl());
        result.setLoginRequired(entry.isLoginRequired());
        result.getDiagnostics().putAll(entry.getDiagnostics());
        if (!entry.isPassed()) {
            fail(result, entry.getStatus(), entry.getMessage());
        }
    }

    private void runPromotionFlow(Browser browser, JdUnionPromotionRequest request, JdUnionConfig config,
            JdUnionPromotionResult result) throws TimeoutException {
        if (!enterPromotionWorkbench(browser, config, result)) {
            return;
        }
        result.setCurrentUrl(browser.getCurrentUrl());

        if (!nativeSetSearchValue(browser, request.getSkuId())) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union search input not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        if (!nativeClickSearchButton(browser)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union search button not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        Extends.sleep(config.getStepDelayMillis() * 2L);

        String searchBody = bodySnippet(browser);
        if (containsAny(searchBody, "抱歉，没有找到相关商品", "没有找到相关商品", "暂无相关商品")) {
            fail(result, CustomCrawlStatus.NOT_FOUND, "JD Union product not found");
            result.getDiagnostics().put("body", searchBody);
            return;
        }
        scrollToProductPromotionArea(browser, config);

        int searchResultCount = readSearchResultCount(searchBody);
        result.getDiagnostics().put("searchResultCount", searchResultCount);
        if (searchResultCount == 0) {
            CustomCrawlStatus status = containsAny(searchBody, "暂无", "没有", "无结果")
                    ? CustomCrawlStatus.NOT_FOUND : CustomCrawlStatus.PAGE_CHANGED;
            fail(result, status, "JD Union promotion entry not found");
            result.getDiagnostics().put("body", searchBody);
            return;
        }
        if (searchResultCount > 1) {
            fail(result, CustomCrawlStatus.MULTIPLE_MATCHED, "JD Union search result is not unique");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        if (!nativeClickPrimaryPromoteButton(browser)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union primary promotion entry not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        waitAndClickText(browser, "已获取权益，继续推广", true, config, 8);
        waitAndClickText(browser, "继续推广", false, config, 3);
        waitTextVisible(browser, request.getMediaType(), config);

        if (!clickByText(browser, request.getMediaType(), true)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union media type option not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        Extends.sleep(config.getStepDelayMillis());
        if (!selectGuideMedia(browser, request.getMediaName(), config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union guide media option not found");
            result.getDiagnostics().put("promotionDialog", collectPromotionDialogDiagnostics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        Extends.sleep(config.getStepDelayMillis());
        if (!clickByText(browser, "选择推广位", true)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union promote slot selector not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        Extends.sleep(config.getStepDelayMillis());
        if (!selectPromotionSlotName(browser, request.getAdSiteName(), config)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union promotion slot name input not found");
            result.getDiagnostics().put("promotionDialog", collectPromotionDialogDiagnostics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        Extends.sleep(config.getStepDelayMillis());
        if (!nativeClickDialogButton(browser, "获取推广链接")) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union get promotion link button not found");
            result.getDiagnostics().put("promotionDialog", collectPromotionDialogDiagnostics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }

        String promotionUrl = "";
        for (int i = 0; i < 10; i++) {
            Extends.sleep(config.getStepDelayMillis());
            promotionUrl = readPromotionUrl(browser);
            if (!Strings.isEmpty(promotionUrl)) {
                break;
            }
        }
        waitAndClickDialogButton(browser, "复制", config, 5);
        if (Strings.isEmpty(promotionUrl)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union promotion link not found");
            result.getDiagnostics().put("promotionDialog", collectPromotionDialogDiagnostics(browser));
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }

        result.setPromotionUrl(promotionUrl);
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("");
    }

    private boolean enterPromotionWorkbench(Browser browser, JdUnionConfig config, JdUnionPromotionResult result)
            throws TimeoutException {
        browser.navigateUrl(config.getOverviewUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        Extends.sleep(config.getStepDelayMillis());
        result.setCurrentUrl(browser.getCurrentUrl());
        if (isLoginRequired(result.getCurrentUrl(), config)) {
            fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                    "JD Union login expired before entering overview page. Finish login in the opened Chrome profile, then retry.");
            return false;
        }

        if (!nativeClickByText(browser, "我要推广", true) && !clickByText(browser, "我要推广", false)) {
            result.getDiagnostics().put("leftMenuMissing", true);
            result.getDiagnostics().put("overviewBody", bodySnippet(browser));
            browser.navigateUrl(config.getWorkbenchUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
            Extends.sleep(config.getStepDelayMillis());
            result.setCurrentUrl(browser.getCurrentUrl());
            if (isLoginRequired(result.getCurrentUrl(), config)) {
                fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                        "JD Union login expired before entering promotion workbench. Finish login in the opened Chrome profile, then retry.");
                return false;
            }
            if (waitPromotionWorkbenchReady(browser, config, result)) {
                return true;
            }
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union promotion workbench not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return false;
        }
        Extends.sleep(config.getStepDelayMillis());

        if (!nativeClickByText(browser, "商品推广", true) && !clickByText(browser, "商品推广", false)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union menu 商品推广 not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return false;
        }

        for (int i = 0; i < 10; i++) {
            Extends.sleep(config.getStepDelayMillis());
            result.setCurrentUrl(browser.getCurrentUrl());
            if (isPromotionWorkbenchReady(browser)) {
                return true;
            }
        }

        fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union 商品推广 page not reached");
        result.getDiagnostics().put("body", bodySnippet(browser));
        return false;
    }

    @SneakyThrows
    private WebBrowserConfig createBrowserConfig(JdUnionPromotionRequest request, JdUnionConfig jdConfig) {
        WebBrowserConfig config = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        config.setProfileDataPath(profileManager.resolveProfileDataPath(request.getProfileName()));
        config.setHeadless(jdConfig.isHeadless());
        config.setFingerprintEnabled(jdConfig.isFingerprintEnabled());
        config.setFingerprintHeadless(jdConfig.isHeadless());
        if (Strings.isEmpty(config.getConfigureScriptExecutorType())) {
            config.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        }
        String cookieContainerType = appConfig.getBrowser().getCookieContainerType();
        if (Strings.isEmpty(cookieContainerType)) {
            config.setCookieContainer(new MemoryCookieContainer());
        } else {
            config.setCookieContainer(Reflects.newInstance(Class.forName(cookieContainerType)));
        }
        return config;
    }

    private JdUnionPromotionRequest normalizeRequest(JdUnionPromotionRequest request, JdUnionConfig config) {
        if (request == null) {
            request = new JdUnionPromotionRequest();
        }
        if (Strings.isEmpty(request.getProfileName())) {
            request.setProfileName(Strings.isEmpty(config.getProfileName()) ? profileManager.defaultProfileName() : config.getProfileName());
        }
        request.setProfileName(profileManager.normalizeProfileName(request.getProfileName()));
        if (Strings.isEmpty(request.getMediaType())) {
            request.setMediaType(config.getDefaultMediaType());
        }
        if (Strings.isEmpty(request.getMediaName())) {
            request.setMediaName(config.getDefaultMediaName());
        }
        if (Strings.isEmpty(request.getOutputPath())) {
            request.setOutputPath(config.getDefaultOutputPath());
        }
        return request;
    }

    private JdUnionPromotionResult createResult(JdUnionPromotionRequest request) {
        JdUnionPromotionResult result = new JdUnionPromotionResult();
        result.setTaskType(TASK_TYPE);
        result.setStatus(CustomCrawlStatus.FAILED);
        result.setSkuId(request.getSkuId());
        result.setAdSiteName(request.getAdSiteName());
        result.setMediaType(request.getMediaType());
        result.setMediaName(request.getMediaName());
        result.setProfileName(request.getProfileName());
        return result;
    }

    private boolean isLoginRequired(String currentUrl, JdUnionConfig config) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return currentUrl.startsWith(config.getLoginUrlPrefix())
                || lower.contains("passport.jd.com")
                || lower.contains("/login");
    }

    private boolean isLoggedInUrl(String currentUrl) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return lower.contains("union.jd.com/overview")
                || lower.contains("union.jd.com/promanager")
                || lower.contains("union.jd.com/manager");
    }

    private boolean isOverviewUrl(String currentUrl, JdUnionConfig config) {
        if (Strings.isEmpty(currentUrl) || Strings.isEmpty(config.getOverviewUrl())) {
            return false;
        }
        return currentUrl.startsWith(config.getOverviewUrl());
    }

    private boolean waitPromotionWorkbenchReady(Browser browser, JdUnionConfig config, JdUnionPromotionResult result) {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getInitialPageTimeoutSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Extends.sleep(Math.max(1000, config.getStepDelayMillis()));
            result.setCurrentUrl(browser.getCurrentUrl());
            if (isPromotionWorkbenchReady(browser)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPromotionWorkbenchReady(Browser browser) {
        Boolean ready = browser.executeScript("function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function text(el){return ((el.innerText||el.value||el.getAttribute('title')||el.getAttribute('placeholder')||'')+'').replace(/\\s+/g,'');}" +
                "var inputs=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var hasInput=false;for(var i=0;i<inputs.length;i++){var e=inputs[i],meta=text(e)+(e.name||'')+(e.id||'')+(e.className||'');" +
                "var box=e;for(var d=0;box&&d<5;d++,box=box.parentElement){meta+=text(box);}" +
                "if(visible(e)&&(/商品|sku|关键词|keyword|search/i).test(meta)){hasInput=true;break;}}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,span,div'));" +
                "var hasButton=false;for(var j=0;j<nodes.length;j++){var t=text(nodes[j]);" +
                "if(visible(nodes[j])&&(t==='搜索'||t==='搜索全部商品')){hasButton=true;break;}}" +
                "return hasInput&&hasButton;");
        return Boolean.TRUE.equals(ready);
    }

    private void fail(JdUnionPromotionResult result, CustomCrawlStatus status, String message) {
        result.setStatus(status);
        result.setMessage(message == null ? "" : message);
    }

    private void publishDirectResult(JdUnionPromotionResult result) {
        try {
            publishEvent(EVENT_PROMOTION_RESULT, RemotingEventArgs.direct(result));
        } catch (Exception e) {
            log.debug("Ignore direct event publish outside remoting context: {}", e.getMessage());
        }
    }

    private List<JdUnionPromotionRequest> loadBatchItems(JdUnionBatchRequest request) {
        if (request == null) {
            return new ArrayList<JdUnionPromotionRequest>();
        }
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            return request.getItems();
        }
        if (Strings.isEmpty(request.getInputPath())) {
            return new ArrayList<JdUnionPromotionRequest>();
        }
        try {
            return objectMapper.readValue(new File(request.getInputPath()),
                    new TypeReference<List<JdUnionPromotionRequest>>() {
                    });
        } catch (Exception e) {
            throw new InvalidException("Read JD union batch input fail, path={}", request.getInputPath(), e);
        }
    }

    private boolean nativeSetSearchValue(Browser browser, String value) {
        String selector = "[data-rx-jd-search-input='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-search-input';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function meta(el){var v=(el.getAttribute('placeholder')||'')+(el.getAttribute('aria-label')||'')+(el.name||'')+(el.id||'')+(el.className||'');" +
                "var p=el;for(var d=0;p&&d<5;d++,p=p.parentElement){v+=norm(p.innerText||p.getAttribute('title'));}return v;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var chosen=null;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];" +
                "if(visible(e)&&(/商品|sku|关键词|搜索|keyword|search/i).test(meta(e))){chosen=e;break;}}" +
                "if(!chosen){return false;}" +
                "chosen.setAttribute(attr,'1');chosen.scrollIntoView({block:'center',inline:'center'});chosen.focus();return true;");
        if (!Boolean.TRUE.equals(marked)) {
            return setSearchValueByScript(browser, value);
        }
        try {
            browser.elementPress(selector, value, false);
            return true;
        } catch (Exception e) {
            return setSearchValueByScript(browser, value);
        }
    }

    private boolean setSearchValueByScript(Browser browser, String value) {
        Boolean ok = browser.executeScript("var value=arguments[0];" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function meta(el){var v=(el.getAttribute('placeholder')||'')+(el.getAttribute('aria-label')||'')+(el.name||'')+(el.id||'')+(el.className||'');" +
                "var p=el;for(var d=0;p&&d<5;d++,p=p.parentElement){v+=norm(p.innerText||p.getAttribute('title'));}return v;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var chosen=null;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];" +
                "if(visible(e)&&(/商品|sku|关键词|搜索|keyword|search/i).test(meta(e))){chosen=e;break;}}" +
                "if(!chosen){return false;}" +
                "chosen.focus();" +
                "var setter=Object.getOwnPropertyDescriptor(chosen.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set;" +
                "setter.call(chosen,value);" +
                "chosen.dispatchEvent(new Event('input',{bubbles:true}));" +
                "chosen.dispatchEvent(new Event('change',{bubbles:true}));" +
                "return true;", value);
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickSearchButton(Browser browser) {
        String selector = "[data-rx-jd-search-button='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-search-button';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function mark(root){if(!root){return false;}var nodes=Array.prototype.slice.call(root.querySelectorAll('button,a,span,div'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],t=text(e);if(visible(e)&&(t==='搜索全部商品'||t==='搜索')){e.setAttribute(attr,'1');e.scrollIntoView({block:'center',inline:'center'});return true;}}return false;}" +
                "var input=document.querySelector('[data-rx-jd-search-input=\"1\"]');" +
                "var p=input;for(var d=0;p&&d<7;d++,p=p.parentElement){if(mark(p)){return true;}}" +
                "return mark(document.body);");
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

    private boolean nativeClickPrimaryPromoteButton(Browser browser) {
        String selector = "[data-rx-jd-primary-promote='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-primary-promote';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function productBox(el){var p=el;for(var d=0;p&&d<8;d++,p=p.parentElement){var t=norm(p.innerText);" +
                "if(t.indexOf('为您推荐以下相似商品')>=0){return null;}" +
                "if(t.indexOf('佣金比例')>=0&&t.indexOf('预估收益')>=0&&t.indexOf('到手价')>=0){return p;}}return null;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,span,div'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)||text(e)!=='我要推广'){continue;}" +
                "if(productBox(e)){var target=e.closest('button,a')||e;target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;}}" +
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

    private void scrollToProductPromotionArea(Browser browser, JdUnionConfig config) {
        browser.executeScript("var marker=null;" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,span,div'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],t=norm(e.innerText||e.value||e.getAttribute('title'));" +
                "if(visible(e)&&t==='我要推广'){var p=e;for(var d=0;p&&d<8;d++,p=p.parentElement){var pt=norm(p.innerText);" +
                "if(pt.indexOf('佣金比例')>=0&&pt.indexOf('预估收益')>=0&&pt.indexOf('到手价')>=0&&pt.indexOf('为您推荐以下相似商品')<0){marker=e;break;}}" +
                "if(marker){break;}}}" +
                "if(marker){marker.scrollIntoView({block:'center',inline:'center'});window.scrollBy(0,180);}else{window.scrollBy(0,Math.floor(window.innerHeight*0.7));}");
        Extends.sleep(Math.max(800, config.getStepDelayMillis()));
    }

    private boolean selectGuideMedia(Browser browser, String mediaName, JdUnionConfig config) {
        if (Strings.isEmpty(mediaName)) {
            return true;
        }
        if (!nativeClickGuideMediaDropdown(browser)) {
            return false;
        }
        Extends.sleep(Math.max(1000, config.getStepDelayMillis()));
        if (nativeClickElementSelectOption(browser, mediaName) || nativeClickDropdownOption(browser, mediaName, "data-rx-jd-media-dropdown")) {
            Extends.sleep(config.getStepDelayMillis());
            return true;
        }
        nativeOpenGuideMediaDropdownByEvent(browser);
        Extends.sleep(Math.max(1000, config.getStepDelayMillis()));
        if (nativeClickElementSelectOption(browser, mediaName) || nativeClickDropdownOption(browser, mediaName, "data-rx-jd-media-dropdown")) {
            Extends.sleep(config.getStepDelayMillis());
            return true;
        }
        return selectedGuideMediaEquals(browser, mediaName);
    }

    private boolean nativeClickGuideMediaDropdown(Browser browser) {
        String selector = "[data-rx-jd-media-dropdown='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-media-dropdown';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+'],[data-rx-jd-media-input]')).forEach(function(e){e.removeAttribute(attr);e.removeAttribute('data-rx-jd-media-input');});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function cls(el){return ((el.className||'')+' '+(el.id||'')+' '+(el.getAttribute('role')||'')).toLowerCase();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "function rect(el){return el.getBoundingClientRect();}" +
                "var inputs=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var input=null;for(var i=0;i<inputs.length;i++){var e=inputs[i],meta=norm(e.getAttribute('placeholder')||e.value||e.getAttribute('aria-label'));" +
                "if(visible(e)&&meta.indexOf('导购媒体')>=0){input=e;break;}}" +
                "if(!input){var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p')),label=null;" +
                "for(var l=0;l<labels.length;l++){var lt=norm(labels[l].innerText||labels[l].textContent);if(visible(labels[l])&&lt.indexOf('所属导购媒体')>=0&&lt.length<=14){label=labels[l];break;}}" +
                "if(label){var lr=rect(label),best=null,bestScore=999999;for(var j=0;j<inputs.length;j++){var n=inputs[j];if(!visible(n)){continue;}var nr=rect(n),dy=Math.abs((nr.top+nr.bottom)/2-(lr.top+lr.bottom)/2),dx=nr.left-lr.right;" +
                "if(dx>=-20&&dy<50){var score=dy*20+Math.max(0,dx);if(score<bestScore){best=n;bestScore=score;}}}input=best;}}" +
                "if(!input){return false;}input.setAttribute('data-rx-jd-media-input','1');" +
                "var target=input;" +
                "target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;");
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

    private boolean nativeOpenGuideMediaDropdownByEvent(Browser browser) {
        Boolean ok = browser.executeScript("var input=document.querySelector('[data-rx-jd-media-input=\"1\"]');" +
                "if(!input){return false;}var select=input.closest('.el-select')||input.parentElement||input;" +
                "function fire(el,type){var e=new MouseEvent(type,{bubbles:true,cancelable:true,view:window});el.dispatchEvent(e);}" +
                "input.scrollIntoView({block:'center',inline:'center'});input.focus();" +
                "fire(input,'mousedown');fire(input,'mouseup');fire(input,'click');" +
                "fire(select,'mousedown');fire(select,'mouseup');fire(select,'click');return true;");
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickElementSelectOption(Browser browser, String mediaName) {
        String selector = "[data-rx-jd-el-select-option='1']";
        Boolean marked = browser.executeScript("var target=norm(arguments[0]),attr='data-rx-jd-el-select-option';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('.el-select-dropdown__item, .el-select-dropdown li, [role=\"option\"]'));" +
                "var best=null,bestScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var text=norm(e.innerText||e.textContent||e.getAttribute('title'));if(text!==target){continue;}" +
                "var r=e.getBoundingClientRect(),score=r.width*r.height/100+Math.abs(r.top-250);" +
                "if(score<bestScore){best=e;bestScore=score;}}" +
                "if(!best){return false;}" +
                "best.setAttribute(attr,'1');best.scrollIntoView({block:'nearest',inline:'center'});return true;", mediaName);
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

    private boolean selectedGuideMediaEquals(Browser browser, String mediaName) {
        Boolean ok = browser.executeScript("var target=norm(arguments[0]);" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var trigger=document.querySelector('[data-rx-jd-media-dropdown=\"1\"]');" +
                "if(!trigger||!visible(trigger)){return false;}" +
                "return norm(trigger.innerText||trigger.value||trigger.getAttribute('title'))===target;", mediaName);
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeClickLabelControl(Browser browser, String labelText, String attr) {
        String selector = "[" + attr + "='1']";
        Boolean marked = browser.executeScript("var labelText=norm(arguments[0]),attr=arguments[1];" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "function rect(el){return el.getBoundingClientRect();}" +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('placeholder'));}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p'));" +
                "var label=null;for(var i=0;i<labels.length;i++){var lt=text(labels[i]);" +
                "if(visible(labels[i])&&lt.indexOf(labelText)>=0&&lt.length<=labelText.length+8){label=labels[i];break;}}" +
                "if(!label){return false;}var lr=rect(label),nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,input,textarea,div,span'));" +
                "var best=null,bestScore=999999;" +
                "for(var j=0;j<nodes.length;j++){var e=nodes[j];if(!visible(e)||e===label||label.contains(e)||e.contains(label)){continue;}" +
                "var t=text(e);if(t.indexOf(labelText)>=0||t.indexOf('新增导购媒体')>=0){continue;}" +
                "var er=rect(e),dy=Math.abs((er.top+er.bottom)/2-(lr.top+lr.bottom)/2),dx=er.left-lr.right,area=er.width*er.height;" +
                "if(dx<-20||dy>50||er.width<60||er.height<20||area>50000){continue;}" +
                "var score=dy*20+Math.max(0,dx)+area/500;" +
                "if(score<bestScore){best=e;bestScore=score;}}" +
                "if(!best){return false;}" +
                "var target=best.closest('[role=\"combobox\"],button,a')||best;" +
                "target.setAttribute(attr,'1');target.scrollIntoView({block:'center',inline:'center'});return true;", labelText, attr);
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

    private boolean nativeClickDropdownOption(Browser browser, String optionText, String triggerAttr) {
        String selector = "[data-rx-jd-dropdown-option='1']";
        Boolean marked = browser.executeScript("var target=norm(arguments[0]),triggerAttr=arguments[1],attr='data-rx-jd-dropdown-option';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function cls(el){return ((el.className||'')+' '+(el.id||'')+' '+(el.getAttribute('role')||'')).toLowerCase();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "function popupScore(el){for(var p=el;p&&p!==document.body;p=p.parentElement){if(/dropdown|popover|popper|popup|menu|select|option|list|combobox/.test(cls(p))){return 0;}}return 120;}" +
                "function clickTarget(el){var direct=el.closest('li,button,a,[role=\"option\"],[role=\"menuitem\"]');if(direct){return direct;}" +
                "var best=el;for(var p=el.parentElement,d=0;p&&p!==document.body&&d<4;d++,p=p.parentElement){var r=p.getBoundingClientRect(),t=norm(p.innerText||p.value||p.getAttribute('title'));" +
                "if(t.indexOf(target)>=0&&r.width<=420&&r.height<=80){best=p;}}return best;}" +
                "var trigger=document.querySelector('['+triggerAttr+'=\"1\"]'),tr=trigger?trigger.getBoundingClientRect():null;" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('li,div,span,p,a,button,[role=\"option\"],[role=\"menuitem\"]'));" +
                "var best=null,bestScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var text=norm(e.innerText||e.value||e.getAttribute('title'));if(text!==target&&text.indexOf(target)<0){continue;}" +
                "if(text.indexOf('所属导购媒体')>=0||text.indexOf('新增导购媒体')>=0||text.indexOf('请选择导购媒体')>=0){continue;}" +
                "if(trigger&&(trigger.contains(e)||e.contains(trigger))){continue;}" +
                "var r=e.getBoundingClientRect(),area=r.width*r.height;" +
                "if(r.width<12||r.height<12||area>60000){continue;}" +
                "var score=(text===target?0:60)+popupScore(e)+area/80;" +
                "if(tr){if(r.top<tr.bottom-25){score+=220;}score+=Math.abs(r.left-tr.left)*0.7+Math.abs(r.top-tr.bottom);}" +
                "if(score<bestScore){best=e;bestScore=score;}}" +
                "if(!best){return false;}" +
                "var targetNode=clickTarget(best);" +
                "targetNode.setAttribute(attr,'1');targetNode.scrollIntoView({block:'nearest',inline:'center'});return true;", optionText, triggerAttr);
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

    private boolean selectPromotionSlotName(Browser browser, String slotName, JdUnionConfig config) {
        if (Strings.isEmpty(slotName)) {
            return false;
        }
        if (nativeClickPromotionSlotDropdown(browser)) {
            Extends.sleep(Math.max(1000, config.getStepDelayMillis()));
            if (nativeClickElementSelectOption(browser, slotName) || nativeClickDropdownOption(browser, slotName, "data-rx-jd-slot-dropdown")) {
                Extends.sleep(config.getStepDelayMillis());
                return selectedPromotionSlotEquals(browser, slotName);
            }
        }
        return nativeSetPromotionSlotName(browser, slotName);
    }

    private boolean nativeClickPromotionSlotDropdown(Browser browser) {
        String selector = "[data-rx-jd-slot-dropdown='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-slot-dropdown';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled;}" +
                "function rect(el){return el.getBoundingClientRect();}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p'));" +
                "var label=null;for(var i=0;i<labels.length;i++){var lt=norm(labels[i].innerText||labels[i].textContent);" +
                "if(visible(labels[i])&&lt.indexOf('推广位名称')>=0&&lt.length<=10){label=labels[i];break;}}" +
                "if(!label){return false;}var lr=rect(label),nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var best=null,bestScore=999999;" +
                "for(var j=0;j<nodes.length;j++){var e=nodes[j];if(!visible(e)){continue;}var er=rect(e);" +
                "var dy=Math.abs((er.top+er.bottom)/2-(lr.top+lr.bottom)/2),dx=er.left-lr.right;" +
                "if(dx>=-20&&dy<50){var score=dy*20+Math.max(0,dx);if(score<bestScore){best=e;bestScore=score;}}}" +
                "if(!best){return false;}best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});best.focus();return true;");
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

    private boolean selectedPromotionSlotEquals(Browser browser, String slotName) {
        Boolean ok = browser.executeScript("var target=norm(arguments[0]);" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "var input=document.querySelector('[data-rx-jd-slot-dropdown=\"1\"],[data-rx-jd-slot-name-input=\"1\"]');" +
                "return !!input&&norm(input.value||input.innerText||input.getAttribute('title'))===target;", slotName);
        return Boolean.TRUE.equals(ok);
    }

    private boolean nativeSetPromotionSlotName(Browser browser, String value) {
        String selector = "[data-rx-jd-slot-name-input='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-slot-name-input';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&!el.readOnly;}" +
                "function rect(el){return el.getBoundingClientRect();}" +
                "var labels=Array.prototype.slice.call(document.querySelectorAll('label,span,div,p'));" +
                "var label=null;for(var i=0;i<labels.length;i++){var t=norm(labels[i].innerText||labels[i].textContent);" +
                "if(visible(labels[i])&&t.indexOf('推广位名称')>=0&&t.length<=8){label=labels[i];break;}}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "if(label){var lr=rect(label),best=null,bestScore=999999;" +
                "for(var j=0;j<nodes.length;j++){var e=nodes[j];if(!visible(e)){continue;}var er=rect(e);" +
                "var dy=Math.abs((er.top+er.bottom)/2-(lr.top+lr.bottom)/2),dx=er.left-lr.right;" +
                "if(dx>=-20&&dy<45){var score=dy*10+Math.max(0,dx);if(score<bestScore){best=e;bestScore=score;}}}" +
                "if(best){best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});best.focus();return true;}}" +
                "var fallback=null,fallbackTop=-1;" +
                "for(var k=0;k<nodes.length;k++){var input=nodes[k],ir=rect(input);if(visible(input)&&!(input.value||'').trim()&&ir.top>fallbackTop){fallback=input;fallbackTop=ir.top;}}" +
                "if(fallback){fallback.setAttribute(attr,'1');fallback.scrollIntoView({block:'center',inline:'center'});fallback.focus();return true;}" +
                "return false;");
        if (!Boolean.TRUE.equals(marked)) {
            return false;
        }
        try {
            browser.elementPress(selector, value, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nativeClickByText(Browser browser, String text, boolean exact) {
        String selector = "[data-rx-jd-click-target='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-click-target', target=norm(arguments[0]), exact=arguments[1];" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,li,span,div,p'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var txt=norm(e.innerText||e.value||e.getAttribute('title'));" +
                "if(!txt){continue;}var matched=exact?txt===target:txt.indexOf(target)>=0;" +
                "if(matched){e.setAttribute(attr,'1');e.scrollIntoView({block:'center',inline:'center'});return true;}}" +
                "return false;", text, exact);
        if (!Boolean.TRUE.equals(marked)) {
            return clickByText(browser, text, exact);
        }
        try {
            browser.elementClick(selector, false);
            return true;
        } catch (Exception e) {
            return clickByText(browser, text, exact);
        }
    }

    private boolean clickByText(Browser browser, String text, boolean exact) {
        if (Strings.isEmpty(text)) {
            return false;
        }
        String selector = "[data-rx-jd-click-target='1']";
        Boolean marked = browser.executeScript("var attr='data-rx-jd-click-target', target=norm(arguments[0]), exact=arguments[1];" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,li,span,div,p,input,textarea'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var txt=norm(e.innerText||e.value||e.getAttribute('title')||e.getAttribute('placeholder'));" +
                "if(!txt){continue;}var matched=exact?txt===target:txt.indexOf(target)>=0;" +
                "if(matched){e.setAttribute(attr,'1');e.scrollIntoView({block:'center',inline:'center'});return true;}}" +
                "return false;", text, exact);
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

    private boolean nativeClickDialogButton(Browser browser, String text) {
        if (Strings.isEmpty(text)) {
            return false;
        }
        String selector = "[data-rx-jd-dialog-button='1']";
        Boolean marked = browser.executeScript("var target=norm(arguments[0]),attr='data-rx-jd-dialog-button';" +
                "Array.prototype.slice.call(document.querySelectorAll('['+attr+']')).forEach(function(e){e.removeAttribute(attr);});" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0&&!el.disabled&&el.getAttribute('aria-disabled')!=='true';}" +
                "function text(el){return norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('aria-label'));}" +
                "function inDialog(el){for(var p=el;p&&p!==document.body;p=p.parentElement){var t=norm(p.innerText);" +
                "if(t.indexOf('生成推广链接')>=0){return true;}}return false;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,[role=\"button\"],span,div'));" +
                "var best=null,bestScore=999999;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var t=text(e);if(!t){continue;}" +
                "var exact=t===target,contains=t.indexOf(target)>=0&&t.indexOf('取消')<0;" +
                "if(!exact&&!contains){continue;}" +
                "var button=e.closest('button,a,[role=\"button\"]')||e;if(!visible(button)){continue;}" +
                "var bt=text(button);if(bt.indexOf('取消')>=0&&target.indexOf('取消')<0){continue;}" +
                "var r=button.getBoundingClientRect(),score=(exact?0:100)+(inDialog(button)?0:200)+r.width*r.height/1000;" +
                "if(score<bestScore){best=button;bestScore=score;}}" +
                "if(!best){return false;}" +
                "best.setAttribute(attr,'1');best.scrollIntoView({block:'center',inline:'center'});return true;", text);
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

    private int readSearchResultCount(String body) {
        if (Strings.isEmpty(body)) {
            return -1;
        }
        Matcher matcher = SEARCH_RESULT_COUNT_PATTERN.matcher(body);
        if (!matcher.find()) {
            return -1;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private boolean waitTextVisible(Browser browser, String text, JdUnionConfig config) {
        long deadline = System.currentTimeMillis() + Math.max(1, config.getInitialPageTimeoutSeconds()) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Extends.sleep(Math.max(1000, config.getStepDelayMillis()));
            if (Boolean.TRUE.equals(browser.executeScript("return (document.body && document.body.innerText || '').indexOf(arguments[0]) >= 0;", text))) {
                return true;
            }
        }
        return false;
    }

    private boolean waitAndClickText(Browser browser, String text, boolean exact, JdUnionConfig config, int maxSeconds) {
        long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Extends.sleep(Math.max(1000, config.getStepDelayMillis()));
            if (clickByText(browser, text, exact)) {
                Extends.sleep(config.getStepDelayMillis());
                return true;
            }
        }
        return false;
    }

    private boolean waitAndClickDialogButton(Browser browser, String text, JdUnionConfig config, int maxSeconds) {
        long deadline = System.currentTimeMillis() + Math.max(1, maxSeconds) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Extends.sleep(Math.max(1000, config.getStepDelayMillis()));
            if (nativeClickDialogButton(browser, text)) {
                Extends.sleep(config.getStepDelayMillis());
                return true;
            }
        }
        return false;
    }

    private String readPromotionUrl(Browser browser) {
        return browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function pick(v){if(!v){return '';}var m=String(v).match(/https?:\\/\\/[^\\s\"'<>]+/);return m?m[0]:'';}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function score(el){var t=norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('placeholder'));" +
                "var p=el;for(var i=0;i<5&&p;i++,p=p.parentElement){t+=norm(p.innerText||p.getAttribute('title'));}" +
                "var s=0;if(/优惠券|券|coupon/.test(t)){s+=1000;}if(/推广链接|短链接/.test(t)){s+=100;}return s;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var best='';var bestScore=-1;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}var v=pick(e.value||e.innerText);if(!v){continue;}" +
                "var sc=score(e);if(sc>bestScore){best=v;bestScore=sc;}}" +
                "if(best){return best;}" +
                "var body=document.body?document.body.innerText:'';" +
                "var coupon=body.match(/https?:\\/\\/[^\\s\"'<>]+/g)||[];" +
                "for(var j=0;j<coupon.length;j++){if(/u\\.jd\\.com/i.test(coupon[j])){return coupon[j];}}" +
                "return coupon[0]||'';");
    }

    private Map<String, Object> collectPromotionDialogDiagnostics(Browser browser) {
        return browser.executeScript("function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "function visible(el){var st=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return st.display!=='none'&&st.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "function parents(el){var list=[];for(var p=el.parentElement;p&&p!==document.body&&list.length<5;p=p.parentElement){list.push({tag:p.tagName,role:p.getAttribute('role')||'',className:norm(p.className).substring(0,120),text:norm(p.innerText).substring(0,80)});}return list;}" +
                "function item(el){var r=el.getBoundingClientRect();return {tag:el.tagName,role:el.getAttribute('role')||'',className:norm(el.className).substring(0,120),text:norm(el.innerText||el.value||el.getAttribute('title')||el.getAttribute('placeholder')).substring(0,80)," +
                "value:norm(el.value).substring(0,120),placeholder:norm(el.getAttribute('placeholder')).substring(0,80),disabled:!!el.disabled,readOnly:!!el.readOnly,top:Math.round(r.top),left:Math.round(r.left),width:Math.round(r.width),height:Math.round(r.height),parents:parents(el)};}" +
                "return {url:location.href,inputs:Array.prototype.slice.call(document.querySelectorAll('input,textarea')).filter(visible).map(item)," +
                "buttons:Array.prototype.slice.call(document.querySelectorAll('button,a,[role=\"button\"],[role=\"option\"],li')).filter(visible).map(item)," +
                "body:norm(document.body?document.body.innerText:'').substring(0,1200)};");
    }

    private String bodySnippet(Browser browser) {
        String body = browser.executeScript("return document.body ? document.body.innerText : '';");
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
}
