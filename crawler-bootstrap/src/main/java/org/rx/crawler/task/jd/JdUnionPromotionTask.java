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
import org.rx.crawler.task.common.BrowserPreflightResult;
import org.rx.crawler.task.common.BrowserPreflightService;
import org.rx.crawler.task.common.BrowserProfileManager;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.tryClose;

@Slf4j
@Service
@RequiredArgsConstructor
public class JdUnionPromotionTask implements CustomCrawlTask<JdUnionPromotionRequest, JdUnionPromotionResult>, JdUnionCrawlContract {
    private static final String TASK_TYPE = "jdUnionPromotion";

    private final AppConfig appConfig;
    private final BrowserProfileManager profileManager;
    private final BrowserPreflightService preflightService;
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
        return promotion(request);
    }

    @Override
    public JdUnionPromotionResult promotion(JdUnionPromotionRequest request) {
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
            results.add(promotion(item));
        }
        return results;
    }

    private String buildWorkbenchUrl(String workbenchUrl, String skuId) {
        if (Strings.isEmpty(skuId) || workbenchUrl.contains("keywords=")) {
            return workbenchUrl;
        }
        String separator = workbenchUrl.contains("?") ? "&" : "?";
        return workbenchUrl + separator + "keywords=" + URLEncoder.encode(skuId, StandardCharsets.UTF_8);
    }

    private JdUnionPromotionResult executeInternal(JdUnionPromotionRequest rawRequest, boolean doPromotion) {
        JdUnionConfig jdConfig = appConfig.getCustom().getJdUnion();
        JdUnionPromotionRequest request = normalizeRequest(rawRequest, jdConfig);
        JdUnionPromotionResult result = createResult(request);
        BrowserProfileManager.ProfileLease lease = null;
        try {
            lease = profileManager.acquire(request.getProfileName(), createBrowserConfig(request, jdConfig));
            Browser browser = lease.getBrowser();

            if (!runPreflight(browser, request, jdConfig, result)) {
                return result;
            }
            if (!checkLogin(browser, request, jdConfig, result, lease)) {
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

    private boolean runPreflight(Browser browser, JdUnionPromotionRequest request, JdUnionConfig config,
            JdUnionPromotionResult result) {
        boolean forcePreflight = request.getForcePreflight() == null ? config.isForcePreflight() : request.getForcePreflight();
        BrowserPreflightResult preflight = preflightService.check(browser, request.getProfileName(), config, forcePreflight);
        result.setFingerprintPassed(preflight.isPassed());
        result.getDiagnostics().put("preflight", preflight);
        if (preflight.isPassed()) {
            return true;
        }

        fail(result, CustomCrawlStatus.BROWSER_FINGERPRINT_CHECK_FAILED, preflight.getMessage());
        return false;
    }

    private boolean checkLogin(Browser browser, JdUnionPromotionRequest request, JdUnionConfig config,
            JdUnionPromotionResult result, BrowserProfileManager.ProfileLease lease) throws TimeoutException {
        browser.navigateUrl(config.getLoginCheckUrl(), Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        Extends.sleep(config.getStepDelayMillis());
        result.setCurrentUrl(browser.getCurrentUrl());
        if (!isLoginRequired(result.getCurrentUrl(), config)) {
            return true;
        }

        result.setLoginRequired(true);
        boolean keepOpen = request.getKeepBrowserOpenOnLoginRequired() == null
                ? config.isKeepBrowserOpenOnLoginRequired() : request.getKeepBrowserOpenOnLoginRequired();
        if (keepOpen && waitLoginCompleted(browser, config, result)) {
            result.setLoginRequired(false);
            result.setCurrentUrl(browser.getCurrentUrl());
            result.getDiagnostics().put("loginWaitCompleted", true);
            return true;
        }

        fail(result, CustomCrawlStatus.LOGIN_REQUIRED,
                "JD Union login required. Finish login in the opened Chrome profile, then retry.");
        if (keepOpen) {
            lease.keepOpen(config.getKeepBrowserOpenSecondsOnLoginRequired());
        }
        return false;
    }

    private boolean waitLoginCompleted(Browser browser, JdUnionConfig config, JdUnionPromotionResult result) {
        int waitSeconds = Math.max(0, config.getLoginWaitSeconds());
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        result.getDiagnostics().put("loginWaitSeconds", waitSeconds);
        while (System.currentTimeMillis() < deadline) {
            Extends.sleep(Math.max(1000, config.getStepDelayMillis()));
            String currentUrl = browser.getCurrentUrl();
            result.setCurrentUrl(currentUrl);
            if (isLoginRequired(currentUrl, config)) {
                continue;
            }
            if (isLoggedInUrl(currentUrl)) {
                return true;
            }
            String body = bodySnippet(browser);
            if (!containsAny(body, "登录", "扫码", "验证码") && containsAny(body, "京东联盟", "我要推广", "推广管理", "商品")) {
                return true;
            }
        }
        return false;
    }

    private void runPromotionFlow(Browser browser, JdUnionPromotionRequest request, JdUnionConfig config,
            JdUnionPromotionResult result) throws TimeoutException {
        browser.navigateUrl(buildWorkbenchUrl(config.getWorkbenchUrl(), request.getSkuId()),
                Browser.BODY_SELECTOR, config.getPageTimeoutSeconds());
        Extends.sleep(config.getStepDelayMillis());
        result.setCurrentUrl(browser.getCurrentUrl());

        if (!nativeSetSearchValue(browser, request.getSkuId())) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union search input not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        if (!nativeClickByText(browser, "搜索全部商品", true) && !nativeClickByText(browser, "搜索", false)) {
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

        long promoteCount = countByText(browser, "我要推广");
        result.getDiagnostics().put("promoteButtonCount", promoteCount);
        if (promoteCount == 0) {
            CustomCrawlStatus status = containsAny(searchBody, "暂无", "没有", "无结果")
                    ? CustomCrawlStatus.NOT_FOUND : CustomCrawlStatus.PAGE_CHANGED;
            fail(result, status, "JD Union promotion entry not found");
            result.getDiagnostics().put("body", searchBody);
            return;
        }
        if (promoteCount > 1) {
            fail(result, CustomCrawlStatus.MULTIPLE_MATCHED, "JD Union search result is not unique");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        clickByText(browser, "我要推广", true);
        Extends.sleep(config.getStepDelayMillis());

        if (!clickByText(browser, request.getMediaType(), true)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union media type option not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        Extends.sleep(config.getStepDelayMillis());
        if (!clickByText(browser, request.getMediaName(), true)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union media name option not found");
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
        if (!clickByText(browser, request.getAdSiteName(), true)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union ad site option not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }
        if (!clickByText(browser, "获取推广链接", false)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union get promotion link button not found");
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
        if (Strings.isEmpty(promotionUrl)) {
            fail(result, CustomCrawlStatus.PAGE_CHANGED, "JD Union promotion link not found");
            result.getDiagnostics().put("body", bodySnippet(browser));
            return;
        }

        result.setPromotionUrl(promotionUrl);
        result.setStatus(CustomCrawlStatus.SUCCESS);
        result.setMessage("");
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
        return lower.contains("union.jd.com/promanager")
                || lower.contains("union.jd.com/manager");
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
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var chosen=null;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],p=(e.getAttribute('placeholder')||'')+(e.getAttribute('aria-label')||'');" +
                "if(visible(e)&&(/商品|sku|关键词|搜索|keyword/i).test(p)){chosen=e;break;}}" +
                "if(!chosen){for(var j=0;j<nodes.length;j++){if(visible(nodes[j])){chosen=nodes[j];break;}}}" +
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
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "var chosen=null;" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i],p=(e.getAttribute('placeholder')||'')+(e.getAttribute('aria-label')||'');" +
                "if(visible(e)&&(/商品|sku|关键词|搜索|keyword/i).test(p)){chosen=e;break;}}" +
                "if(!chosen){for(var j=0;j<nodes.length;j++){if(visible(nodes[j])){chosen=nodes[j];break;}}}" +
                "if(!chosen){return false;}" +
                "chosen.focus();" +
                "var setter=Object.getOwnPropertyDescriptor(chosen.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set;" +
                "setter.call(chosen,value);" +
                "chosen.dispatchEvent(new Event('input',{bubbles:true}));" +
                "chosen.dispatchEvent(new Event('change',{bubbles:true}));" +
                "return true;", value);
        return Boolean.TRUE.equals(ok);
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
        Boolean ok = browser.executeScript("var target=norm(arguments[0]), exact=arguments[1];" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,li,span,div,p,input,textarea'));" +
                "for(var i=0;i<nodes.length;i++){var e=nodes[i];if(!visible(e)){continue;}" +
                "var txt=norm(e.innerText||e.value||e.getAttribute('title')||e.getAttribute('placeholder'));" +
                "if(!txt){continue;}var matched=exact?txt===target:txt.indexOf(target)>=0;" +
                "if(matched){e.scrollIntoView({block:'center',inline:'center'});e.click();return true;}}" +
                "return false;", text, exact);
        return Boolean.TRUE.equals(ok);
    }

    private long countByText(Browser browser, String text) {
        Number count = browser.executeScript("var target=norm(arguments[0]);" +
                "function norm(s){return (s||'').replace(/\\s+/g,'').trim();}" +
                "function visible(el){var s=getComputedStyle(el),r=el.getBoundingClientRect();" +
                "return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('button,a,span'));" +
                "var n=0;for(var i=0;i<nodes.length;i++){var e=nodes[i];if(visible(e)&&norm(e.innerText||e.value)===target){n++;}}" +
                "return n;", text);
        return count == null ? 0 : count.longValue();
    }

    private String readPromotionUrl(Browser browser) {
        return browser.executeScript("function pick(v){if(!v){return '';}var m=String(v).match(/https?:\\/\\/[^\\s\"'<>]+/);return m?m[0]:'';}" +
                "var nodes=Array.prototype.slice.call(document.querySelectorAll('input,textarea'));" +
                "for(var i=0;i<nodes.length;i++){var v=pick(nodes[i].value);if(v){return v;}}" +
                "return pick(document.body?document.body.innerText:'');");
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
