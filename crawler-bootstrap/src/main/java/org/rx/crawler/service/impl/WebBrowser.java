package org.rx.crawler.service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ViewportSize;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventArgs;
import org.rx.core.EventPublisher;
import org.rx.core.Extends;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.core.cache.MemoryCache;
import org.rx.crawler.service.Browser;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.crawler.service.ConfigureScriptExecutor;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.io.Files;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpClientCookieJar;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.require;
import static org.rx.core.Sys.cacheKey;

@Slf4j
public final class WebBrowser extends Disposable implements Browser, EventPublisher<WebBrowser> {
    static final String RESOURCE_JS_PATH = "/bot/root.js";
    static final AtomicInteger chromeIdCounter = new AtomicInteger();
    static final Map<String, String> jsResourceCache = new ConcurrentHashMap<String, String>();

    public final Delegate<WebBrowser, EventArgs> onNavigating = Delegate.create(), onNavigated = Delegate.create();
    final WebBrowserConfig config;
    final HttpClientCookieJar cookieJar;
    final ConfigureScriptExecutor configureScriptExecutor;
    final Set<String> injectedScript = ConcurrentHashMap.newKeySet();
    final Map<String, Page> tabs = new LinkedHashMap<String, Page>();
    @Getter
    @Setter
    private String cookieRegion;
    @Getter
    @Setter
    private long waitMillis = 500;
    private Playwright playwright;
    private BrowserContext context;
    private Page page;
    private Frame activeFrame;
    private volatile String currentHandle;
    private volatile String navigatedUrl, navigatedSelector;
    private double mouseX = 160, mouseY = 120;

    @Override
    public org.rx.crawler.service.BrowserType getType() {
        return org.rx.crawler.service.BrowserType.CHROME;
    }

    public String getCurrentUrl() {
        return page.url();
    }

    public String getCurrentHandle() {
        return currentHandle;
    }

    public BrowserWindowRect getWindowRectangle() {
        checkNotClosed();

        ViewportSize size = page.viewportSize();
        BrowserWindowRect rect = config.getWindowRectangle();
        int x = rect == null ? 0 : rect.getX();
        int y = rect == null ? 0 : rect.getY();
        if (size == null) {
            return rect == null ? new BrowserWindowRect(x, y, 0, 0) : rect;
        }
        return new BrowserWindowRect(x, y, size.width, size.height);
    }

    public void setWindowRectangle(@NonNull BrowserWindowRect rectangle) {
        checkNotClosed();

        if (isMaximized(rectangle)) {
            maximize();
            return;
        }
        page.setViewportSize(rectangle.getWidth(), rectangle.getHeight());
        Map<String, Object> rect = new LinkedHashMap<String, Object>();
        rect.put("x", rectangle.getX());
        rect.put("y", rectangle.getY());
        rect.put("width", rectangle.getWidth());
        rect.put("height", rectangle.getHeight());
        page.evaluate("rect => { try { window.moveTo(rect.x, rect.y); window.resizeTo(rect.width, rect.height); } catch (e) {} }", rect);
    }

    @SneakyThrows
    public WebBrowser(@NonNull WebBrowserConfig config, @NonNull org.rx.crawler.service.BrowserType type) {
        if (type != org.rx.crawler.service.BrowserType.CHROME) {
            throw new InvalidException("Only CHROME is supported by Playwright implementation, type={}", type);
        }
        this.config = config;
        this.waitMillis = config.getWaitMillis();
        this.cookieJar = ifNull(config.getCookieJar(), HttpClientCookieJar.memory());
        this.configureScriptExecutor = org.rx.core.Reflects.newInstance(Class.forName(config.getConfigureScriptExecutorType()), this);
        createChromeContext();
    }

    private void createChromeContext() {
        String userDataPath = resolveUserDataPath();
        Map<String, String> env = new HashMap<String, String>(System.getenv());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));

        BrowserWindowRect rect = config.getWindowRectangle();
        boolean maximized = isMaximized(rect);
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(isHeadless())
                .setAcceptDownloads(true)
                .setIgnoreHTTPSErrors(true)
                .setLocale(ifNull(config.getLocale(), "zh-CN"))
                .setTimezoneId(ifNull(config.getTimezoneId(), "Asia/Shanghai"))
                .setArgs(buildChromeArgs());
        if (maximized) {
            options.setViewportSize((ViewportSize) null);
        } else if (rect != null) {
            options.setViewportSize(rect.getWidth(), rect.getHeight());
            options.setScreenSize(rect.getWidth(), rect.getHeight());
        }
        if (!Strings.isEmpty(config.getDownloadPath())) {
            Files.createDirectory(config.getDownloadPath());
            options.setDownloadsPath(Paths.get(config.getDownloadPath()));
        }
        if (!Strings.isEmpty(config.getUserAgent())) {
            options.setUserAgent(config.getUserAgent());
        }
        if (!Strings.isEmpty(config.getPlaywrightExecutablePath())) {
            options.setExecutablePath(Paths.get(config.getPlaywrightExecutablePath()));
        } else if (!Strings.isEmpty(config.getPlaywrightChannel())) {
            options.setChannel(config.getPlaywrightChannel());
        }

        context = playwright.chromium().launchPersistentContext(Paths.get(userDataPath), options);
        context.setDefaultTimeout(config.getFindElementTimeoutSeconds() * 1000D);
        context.setDefaultNavigationTimeout(config.getPageLoadTimeoutSeconds() * 1000D);
        context.onDialog(dialog -> {
            try {
                dialog.accept();
            } catch (Exception e) {
                log.debug("Ignore dialog accept fail: {}", e.getMessage());
            }
        });
        context.onPage(this::registerTab);
        registerFingerprintScripts();
        registerOptionalRoutes();

        List<Page> pages = context.pages();
        if (pages.isEmpty()) {
            setCurrentPage(context.newPage());
        } else {
            setCurrentPage(pages.get(0));
        }
        focus();
        if (maximized) {
            maximize();
        }
        log.info("Playwright chrome started, profile={}, channel={}, headless={}, humanInput={}",
                userDataPath, config.getPlaywrightChannel(), isHeadless(), config.isHumanInputEnabled());
    }

    private List<String> buildChromeArgs() {
        List<String> args = new ArrayList<String>();
        args.add("--disable-blink-features=AutomationControlled");
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        args.add("--disable-infobars");
        args.add("--disable-features=Translate,OptimizationHints");
        args.add("--autoplay-policy=document-user-activation-required");
        if (isMaximized(config.getWindowRectangle())) {
            args.add("--start-maximized");
            args.add("--window-position=0,0");
        }
        if (!Strings.isEmpty(config.getLocale())) {
            args.add("--lang=" + config.getLocale());
        }
        return args;
    }

    private boolean isMaximized(BrowserWindowRect rect) {
        return rect != null && rect.getWidth() <= 0 && rect.getHeight() <= 0;
    }

    private void registerFingerprintScripts() {
        if (!isChromeFingerprintEnabled()) {
            return;
        }

        try {
            String script = loadJsResource(config.getFingerprintStealthScriptPath()) + "\n;\n" + loadJsResource(config.getFingerprintScriptPath());
            context.addInitScript(script);
            log.info("Playwright fingerprint preload registered, script={}, stealth={}, headless={}",
                    config.getFingerprintScriptPath(), config.getFingerprintStealthScriptPath(), isHeadless());
        } catch (Exception e) {
            if (isChromeFingerprintDiagnostics()) {
                throw new InvalidException("Playwright fingerprint preload init fail, script={}", config.getFingerprintScriptPath(), e);
            }
            log.warn("Playwright fingerprint preload init fail, script={}, error={}", config.getFingerprintScriptPath(), e.getMessage());
        }
    }

    private void registerOptionalRoutes() {
        if (!config.isBlockResourceEnabled()) {
            return;
        }
        context.route("**/*", route -> {
            String resourceType = route.request().resourceType();
            if ("image".equals(resourceType) || "media".equals(resourceType) || "font".equals(resourceType)) {
                route.abort();
                return;
            }
            route.resume();
        });
    }

    private String resolveUserDataPath() {
        String profileDataPath = config.getProfileDataPath();
        if (!Strings.isEmpty(profileDataPath)) {
            Files.createDirectory(profileDataPath);
            return profileDataPath;
        }

        int id = chromeIdCounter.getAndIncrement();
        String dataDir = Paths.get(System.getProperty("java.io.tmpdir"), "rx-mercury-playwright", String.valueOf(id)).toString();
        Files.createDirectory(dataDir);
        return dataDir;
    }

    private boolean isChromeFingerprintEnabled() {
        return Boolean.parseBoolean(System.getProperty("app.browser.fingerprintEnabled", String.valueOf(config.isFingerprintEnabled())));
    }

    private boolean isChromeFingerprintDiagnostics() {
        return Boolean.parseBoolean(System.getProperty("app.browser.fingerprintDiagnostics", String.valueOf(config.isFingerprintDiagnostics())));
    }

    private boolean isHeadless() {
        if (isChromeFingerprintEnabled()) {
            return Boolean.parseBoolean(System.getProperty("app.browser.fingerprintHeadless", String.valueOf(config.isFingerprintHeadless())));
        }
        return Boolean.parseBoolean(System.getProperty("app.browser.headless", String.valueOf(config.isHeadless())));
    }

    private String loadJsResource(String path) {
        return jsResourceCache.computeIfAbsent(path, p -> {
            try (InputStream in = WebBrowser.class.getResourceAsStream(p)) {
                if (in == null) {
                    throw new InvalidException("Resource {} not found", p);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                if (e instanceof InvalidException) {
                    throw (InvalidException) e;
                }
                throw new InvalidException("Resource {} read fail", p, e);
            }
        });
    }

    @Override
    protected void dispose() {
        try {
            if (context != null && !context.isClosed()) {
                context.close();
            }
        } catch (Exception e) {
            log.debug("Ignore playwright context close fail: {}", e.getMessage());
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception e) {
            log.debug("Ignore playwright close fail: {}", e.getMessage());
        }
    }

    @Override
    public void navigateUrl(String url) throws TimeoutException {
        navigateUrl(url, null);
    }

    @Override
    public void navigateUrl(String url, String locatorSelector) throws TimeoutException {
        navigateUrl(url, locatorSelector, config.getFindElementTimeoutSeconds(), null);
    }

    @Override
    public void navigateUrl(String url, String locatorSelector, int timeoutSeconds) throws TimeoutException {
        navigateUrl(url, locatorSelector, timeoutSeconds, null);
    }

    private synchronized void navigateUrl(@NonNull String url, String locatorSelector, int timeoutSeconds, Predicate<Integer> checkComplete) throws TimeoutException {
        checkNotClosed();

        injectedScript.clear();
        activeFrame = null;
        try {
            publishEvent(onNavigating, EventArgs.EMPTY);

            navigate(url);
            navigatedUrl = url;
            navigatedSelector = locatorSelector;

            if (locatorSelector != null) {
                waitElementLocated(locatorSelector, timeoutSeconds, checkComplete);
            }
            publishEvent(onNavigated, EventArgs.EMPTY);
        } catch (TimeoutException e) {
            TraceHandler.INSTANCE.saveExceptionTrace(Thread.currentThread(),
                    String.format("waitElementLocated fail, url=%s selector=%s|%s", url, locatorSelector, timeoutSeconds), e);
            throw e;
        } catch (Exception e) {
            throw new InvalidException("NavigateUrl {} fail", url, e);
        } finally {
            navigatedSelector = null;
        }
    }

    @Override
    public synchronized String saveCookies(boolean reset) throws TimeoutException {
        String u = currentCookieUrl();
        saveCookiesToJar(u);
        String rawCookie = cookieJar.loadForRequest(java.net.URI.create(u));
        if (reset) {
            navigate(u);
            if (Strings.startsWith(u, navigatedUrl) && navigatedSelector != null) {
                waitElementLocated(navigatedSelector);
            }
        }
        return rawCookie;
    }

    @Override
    public synchronized void clearCookies(boolean onlyBrowser) {
        String u = currentCookieUrl();
        if (!Strings.startsWithIgnoreCase(u, "http")) {
            log.warn("clearCookies fail, url={}", u);
            return;
        }
        if (onlyBrowser) {
            context.clearCookies();
            return;
        }

        context.clearCookies();
        cookieJar.clear();
    }

    @Override
    public void setRawCookie(String rawCookie) {
        cookieJar.saveRawCookie(java.net.URI.create(currentCookieUrl()), rawCookie);
    }

    @Override
    public String getRawCookie() {
        return cookieJar.loadForRequest(java.net.URI.create(currentCookieUrl()));
    }

    private String currentCookieUrl() {
        String u = getCurrentUrl();
        return cookieRegion != null ? HttpClient.buildUrl(u, java.util.Collections.singletonMap("_Region", cookieRegion)) : u;
    }

    @Override
    public boolean hasElement(String selector) {
        return findElements(selector, false).any();
    }

    @Override
    public String elementText(String selector) {
        return ifNull(elementsText(selector).firstOrDefault(), "");
    }

    @Override
    public Linq<String> elementsText(String selector) {
        checkNotClosed();

        List<String> values = new ArrayList<String>();
        for (Locator locator : findElements(selector, false)) {
            values.add(locator.innerText());
        }
        return Linq.from(values);
    }

    @Override
    public String elementVal(String selector) {
        return ifNull(elementsVal(selector).firstOrDefault(), "");
    }

    @Override
    public Linq<String> elementsVal(String selector) {
        checkNotClosed();

        List<String> values = new ArrayList<String>();
        for (Locator locator : findElements(selector, false)) {
            try {
                values.add(locator.inputValue());
            } catch (Exception e) {
                values.add(locator.getAttribute("value"));
            }
        }
        return Linq.from(values);
    }

    @Override
    public String elementAttr(String selector, String... attrArgs) {
        return ifNull(elementsAttr(selector, attrArgs).firstOrDefault(), "");
    }

    @Override
    public Linq<String> elementsAttr(String selector, @NonNull String... attrArgs) {
        checkNotClosed();
        require(attrArgs, attrArgs.length > 0);

        String attrName = attrArgs[0], attrVal = attrArgs.length > 1 ? attrArgs[1] : null;
        List<String> values = new ArrayList<String>();
        for (Locator locator : findElements(selector, false)) {
            if (attrVal != null) {
                locator.evaluate("(el, args) => el.setAttribute(args[0], args[1])", java.util.Arrays.asList(attrName, attrVal));
                continue;
            }
            values.add(locator.getAttribute(attrName));
        }
        return Linq.from(values);
    }

    @Override
    public void elementClick(String selector) {
        elementClick(selector, false);
    }

    @Override
    public void elementClick(String selector, boolean waitElementLocated) {
        checkNotClosed();

        Locator locator;
        if (waitElementLocated) {
            try {
                locator = waitElementLocated(selector, config.getFindElementTimeoutSeconds(), null).first();
            } catch (TimeoutException e) {
                throw new InvalidException("Element {} not found", selector, e);
            }
        } else {
            locator = findElement(selector, false);
        }
        if (locator == null) {
            return;
        }

        try {
            if (config.isHumanInputEnabled()) {
                humanClick(locator);
            } else {
                locator.click();
            }
        } catch (PlaywrightException e) {
            log.warn("Playwright click fallback, selector={}, error={}", selector, e.getMessage());
            locator.click();
        }
    }

    @Override
    public void elementPress(String selector, String keys) {
        elementPress(selector, keys, false);
    }

    @Override
    public synchronized void elementPress(String selector, String keys, boolean waitElementLocated) {
        checkNotClosed();

        Locator locator;
        if (waitElementLocated) {
            try {
                locator = waitElementLocated(selector, config.getFindElementTimeoutSeconds(), null).first();
            } catch (TimeoutException e) {
                throw new InvalidException("Element {} not found", selector, e);
            }
        } else {
            locator = findElement(selector, true);
        }
        if (config.isHumanInputEnabled()) {
            humanClick(locator);
            page.keyboard().press("Control+A");
            humanPause(60, 180);
            page.keyboard().press("Backspace");
            humanType(keys);
            return;
        }
        locator.fill(keys);
    }

    @Override
    public void waitElementLocated(String selector) throws TimeoutException {
        waitElementLocated(selector, config.getFindElementTimeoutSeconds());
    }

    @Override
    public void waitElementLocated(String selector, int timeoutSeconds) throws TimeoutException {
        waitElementLocated(selector, timeoutSeconds, null);
    }

    private Linq<Locator> waitElementLocated(String selector, int timeoutSeconds, Predicate<Integer> checkComplete) throws TimeoutException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int evaluatedCount = 0;
        while (System.currentTimeMillis() <= deadline) {
            Linq<Locator> elements = findElements(selector, false);
            if (elements.any()) {
                log.debug("Wait {} located ok", selector);
                return elements;
            }
            if (checkComplete != null && checkComplete.test(++evaluatedCount)) {
                return elements;
            }
            Extends.sleep(Math.max(50, getWaitMillis()));
        }
        throw new TimeoutException(String.format("No such elements '%s'", selector));
    }

    private Locator findElement(String selector, boolean throwOnEmpty) {
        Linq<Locator> elements = findElements(selector, throwOnEmpty);
        if (!elements.any()) {
            if (throwOnEmpty) {
                throw new InvalidException("Element {} not found", selector);
            }
            return null;
        }
        return elements.first();
    }

    private Linq<Locator> findElements(@NonNull String selector, boolean throwOnEmpty) {
        checkNotClosed();

        List<Locator> list = new ArrayList<Locator>();
        try {
            Locator locator = currentFrame().locator(toPlaywrightSelector(selector));
            int count = locator.count();
            for (int i = 0; i < count; i++) {
                list.add(locator.nth(i));
            }
        } catch (Exception e) {
            if (throwOnEmpty) {
                throw e instanceof RuntimeException ? (RuntimeException) e : new InvalidException("Find element {} fail", selector, e);
            }
        }
        if (throwOnEmpty && list.isEmpty()) {
            throw new InvalidException("Element {} not found", selector);
        }
        return Linq.from(list);
    }

    public byte[] screenshotAsBytes(String selector) {
        return findElement(selector, true).screenshot();
    }

    @Override
    public synchronized void injectScript(@NonNull String script) {
        checkNotClosed();
        if (injectedScript.contains(script)) {
            return;
        }

        currentFrame().evaluate("() => {\n" + script + "\n}");
        injectedScript.add(script);
    }

    @Override
    public synchronized <T> T executeScript(@NonNull String script, Object... args) {
        checkNotClosed();

        String rootJs = Cache.<String, String>getInstance(MemoryCache.class)
                .get(cacheKey("injectRootJs"), k -> Browser.readResourceJs(RESOURCE_JS_PATH));
        injectScript(rootJs);
        try {
            Object result = currentFrame().evaluate("(payload) => (function() {\n" + script + "\n}).apply(null, payload)",
                    java.util.Arrays.asList(args));
            return (T) result;
        } catch (PlaywrightException e) {
            throw new InvalidException("ScriptContent:\n{}\n\n{}", script, e.getMessage(), e);
        }
    }

    @Override
    public synchronized <T> T injectAndExecuteScript(String injectScript, String script, Object... args) {
        injectScript(injectScript);
        return executeScript(script, args);
    }

    @Override
    public <T> T executeConfigureScript(String scriptName, Object... args) {
        return configureScriptExecutor.execute(scriptName, args);
    }

    public synchronized void focus() {
        checkNotClosed();
        page.bringToFront();
    }

    public synchronized void maximize() {
        checkNotClosed();

        if (maximizeByCdp()) {
            return;
        }
        try {
            maximizeByWindowsApi();
        } catch (Exception e) {
            log.debug("Playwright browser maximize fallback ignored, error={}", e.getMessage());
        }
    }

    private boolean maximizeByCdp() {
        if (context == null || context.browser() == null || !context.browser().isConnected() || page == null) {
            return false;
        }

        CDPSession session = null;
        try {
            session = context.browser().newBrowserCDPSession();
            String targetId = resolveCurrentTargetId(session);
            if (Strings.isEmpty(targetId)) {
                return false;
            }

            JsonObject windowArgs = new JsonObject();
            windowArgs.addProperty("targetId", targetId);
            JsonObject window = session.send("Browser.getWindowForTarget", windowArgs);
            if (window == null || !window.has("windowId")) {
                return false;
            }

            JsonObject bounds = new JsonObject();
            bounds.addProperty("windowState", "maximized");
            JsonObject args = new JsonObject();
            args.addProperty("windowId", window.get("windowId").getAsInt());
            args.add("bounds", bounds);
            session.send("Browser.setWindowBounds", args);
            return true;
        } catch (Exception e) {
            log.debug("Browser CDP maximize ignored, error={}", e.getMessage());
            return false;
        } finally {
            if (session != null) {
                try {
                    session.detach();
                } catch (Exception e) {
                    log.debug("Ignore browser CDP session detach fail: {}", e.getMessage());
                }
            }
        }
    }

    private void maximizeByWindowsApi() {
        if (page == null) {
            return;
        }
        page.bringToFront();
        Extends.sleep(250);

        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try {
            HWND hwnd = User32.INSTANCE.FindWindow("Chrome_WidgetWin_1", null);
            if (hwnd == null) {
                hwnd = User32.INSTANCE.GetForegroundWindow();
            }
            if (hwnd == null) {
                return;
            }
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_MAXIMIZE);
            User32.INSTANCE.SetForegroundWindow(hwnd);
            Extends.sleep(300);
            page.evaluate("() => { try { window.dispatchEvent(new Event('resize')); } catch (e) {} }");
        } catch (Exception e) {
            log.debug("Windows API maximize ignored, error={}", e.getMessage());
        }
    }

    private String resolveCurrentTargetId(CDPSession session) {
        try {
            JsonObject targets = session.send("Target.getTargets");
            if (targets == null || !targets.has("targetInfos")) {
                return null;
            }

            String currentUrl = page == null ? "" : page.url();
            String firstPageTargetId = null;
            JsonArray targetInfos = targets.getAsJsonArray("targetInfos");
            for (JsonElement element : targetInfos) {
                JsonObject info = element.getAsJsonObject();
                if (!info.has("type") || !"page".equals(info.get("type").getAsString()) || !info.has("targetId")) {
                    continue;
                }

                String targetId = info.get("targetId").getAsString();
                if (Strings.isEmpty(firstPageTargetId)) {
                    firstPageTargetId = targetId;
                }

                String url = info.has("url") ? info.get("url").getAsString() : "";
                if (Strings.isEmpty(currentUrl) || Strings.isEmpty(url)) {
                    continue;
                }
                if (currentUrl.equals(url) || currentUrl.startsWith(url) || url.startsWith(currentUrl)) {
                    return targetId;
                }
            }
            return firstPageTargetId;
        } catch (Exception e) {
            log.debug("Resolve browser target id fail, error={}", e.getMessage());
            return null;
        }
    }

    public synchronized void normalize() {
        checkNotClosed();

        BrowserWindowRect rect = config.getWindowRectangle();
        if (rect != null) {
            setWindowRectangle(rect);
        }
    }

    @Override
    public synchronized void nativeGet(String url) {
        navigate(url);
    }

    public synchronized void switchToDefault() {
        checkNotClosed();
        activeFrame = null;
    }

    public synchronized void switchToFrame(String selector) {
        checkNotClosed();

        ElementHandle handle = findElement(selector, true).elementHandle();
        Frame frame = handle == null ? null : handle.contentFrame();
        if (frame == null) {
            throw new InvalidException("Frame {} not found", selector);
        }
        activeFrame = frame;
    }

    public synchronized String openTab() {
        checkNotClosed();

        Page newPage = context.newPage();
        setCurrentPage(newPage);
        return currentHandle;
    }

    public synchronized void switchTab(@NonNull String winHandle) {
        checkNotClosed();

        Page target = tabs.get(winHandle);
        if (target == null || target.isClosed()) {
            throw new InvalidException("Tab {} not found", winHandle);
        }
        setCurrentPage(target);
    }

    public synchronized void closeTab(@NonNull String winHandle) {
        checkNotClosed();

        Page target = tabs.remove(winHandle);
        if (target != null && !target.isClosed()) {
            target.close();
        }
        if (tabs.isEmpty()) {
            setCurrentPage(context.newPage());
            return;
        }
        if (winHandle.equals(currentHandle)) {
            Map.Entry<String, Page> first = tabs.entrySet().iterator().next();
            currentHandle = first.getKey();
            page = first.getValue();
            activeFrame = null;
        }
    }

    private void navigate(String url) {
        page.navigate(url, new Page.NavigateOptions()
                .setTimeout(config.getPageLoadTimeoutSeconds() * 1000D)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        activeFrame = null;
    }

    private Frame currentFrame() {
        if (activeFrame != null && !activeFrame.isDetached()) {
            return activeFrame;
        }
        return page.mainFrame();
    }

    private String toPlaywrightSelector(String selector) {
        return Strings.startsWith(selector, "/") ? "xpath=" + selector : selector;
    }

    private void humanClick(Locator locator) {
        locator.scrollIntoViewIfNeeded();
        humanPause();
        BoundingBox box = locator.boundingBox();
        if (box == null || box.width <= 0 || box.height <= 0) {
            locator.click();
            humanPause();
            return;
        }

        double x = box.x + randomBetween(box.width * 0.25, box.width * 0.75);
        double y = box.y + randomBetween(box.height * 0.25, box.height * 0.75);
        moveMouseLikeHuman(x, y);
        humanPause(80, 240);
        page.mouse().down();
        humanPause(65, 160);
        page.mouse().up();
        humanPause();
    }

    private void moveMouseLikeHuman(double targetX, double targetY) {
        int steps = randomInt(config.getMouseMoveMinSteps(), config.getMouseMoveMaxSteps());
        double c1x = mouseX + randomBetween(-120, 120);
        double c1y = mouseY + randomBetween(-80, 80);
        double c2x = targetX + randomBetween(-120, 120);
        double c2y = targetY + randomBetween(-80, 80);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double x = cubic(mouseX, c1x, c2x, targetX, t);
            double y = cubic(mouseY, c1y, c2y, targetY, t);
            page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(1));
            humanPause(8, 28);
        }
        mouseX = targetX;
        mouseY = targetY;
    }

    private double cubic(double p0, double p1, double p2, double p3, double t) {
        double u = 1 - t;
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
    }

    private void humanType(String value) {
        if (Strings.isEmpty(value)) {
            return;
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String ch = new String(Character.toChars(codePoint));
            page.keyboard().type(ch, new Keyboard.TypeOptions().setDelay(randomInt(config.getTypingMinDelayMillis(), config.getTypingMaxDelayMillis())));
            offset += Character.charCount(codePoint);
            if (ThreadLocalRandom.current().nextInt(100) < 8) {
                humanPause(250, 900);
            }
        }
    }

    private void humanPause() {
        humanPause(config.getHumanActionMinDelayMillis(), config.getHumanActionMaxDelayMillis());
    }

    private void humanPause(int minMillis, int maxMillis) {
        Extends.sleep(randomInt(minMillis, maxMillis));
    }

    private double randomBetween(double min, double max) {
        return min + ThreadLocalRandom.current().nextDouble() * (max - min);
    }

    private int randomInt(int min, int max) {
        if (max <= min) {
            return Math.max(0, min);
        }
        return ThreadLocalRandom.current().nextInt(Math.max(0, min), max + 1);
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void registerTab(Page p) {
        synchronized (tabs) {
            for (Page value : tabs.values()) {
                if (value == p) {
                    return;
                }
            }
            tabs.put(UUID.randomUUID().toString(), p);
        }
        p.setDefaultTimeout(config.getFindElementTimeoutSeconds() * 1000D);
        p.setDefaultNavigationTimeout(config.getPageLoadTimeoutSeconds() * 1000D);
    }

    private void setCurrentPage(Page target) {
        registerTab(target);
        for (Map.Entry<String, Page> entry : tabs.entrySet()) {
            if (entry.getValue() == target) {
                currentHandle = entry.getKey();
                break;
            }
        }
        page = target;
        activeFrame = null;
        page.bringToFront();
        page.setDefaultTimeout(config.getFindElementTimeoutSeconds() * 1000D);
        page.setDefaultNavigationTimeout(config.getPageLoadTimeoutSeconds() * 1000D);
    }

    private void saveCookiesToJar(String url) {
        if (cookieJar == null || Strings.isEmpty(url) || !Strings.startsWithIgnoreCase(url, "http")) {
            return;
        }

        java.util.List<Cookie> cookies = context.cookies(url);
        if (cookies.isEmpty()) {
            return;
        }
        long nowSeconds = System.currentTimeMillis() / 1000L;
        java.util.List<String> setCookies = new java.util.ArrayList<String>(cookies.size());
        for (Cookie cookie : cookies) {
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            sb.append(cookie.name).append("=").append(cookie.value);
            if (!Strings.isEmpty(cookie.domain)) {
                String host = java.net.URI.create(url).getHost();
                if (!Strings.equals(cookie.domain, host) || Strings.startsWith(cookie.domain, ".")) {
                    sb.append("; Domain=").append(cookie.domain);
                }
            }
            if (!Strings.isEmpty(cookie.path)) {
                sb.append("; Path=").append(cookie.path);
            }
            if (cookie.expires != null && cookie.expires > 0D) {
                long maxAge = Math.max(1L, (long) Math.ceil(cookie.expires - nowSeconds));
                sb.append("; Max-Age=").append(maxAge);
            }
            if (Boolean.TRUE.equals(cookie.secure)) {
                sb.append("; Secure");
            }
            if (Boolean.TRUE.equals(cookie.httpOnly)) {
                sb.append("; HttpOnly");
            }
            if (cookie.sameSite != null) {
                sb.append("; SameSite=").append(toSameSite(cookie.sameSite));
            }
            setCookies.add(sb.toString());
        }
        java.net.URI uri = java.net.URI.create(url);
        cookieJar.clear(uri);
        cookieJar.saveFromResponse(uri, setCookies);
    }

    private String toSameSite(SameSiteAttribute sameSite) {
        switch (sameSite) {
            case STRICT:
                return "Strict";
            case LAX:
                return "Lax";
            case NONE:
                return "None";
            default:
                return null;
        }
    }
}
