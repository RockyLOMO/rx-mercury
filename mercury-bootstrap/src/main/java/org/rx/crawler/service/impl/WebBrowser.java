package org.rx.crawler.service.impl;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.ie.InternetExplorerDriverService;
import org.openqa.selenium.ie.InternetExplorerOptions;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;
import org.rx.bean.DateTime;
import org.rx.core.cache.MemoryCache;
import org.rx.crawler.Browser;
import org.rx.crawler.BrowserType;
import org.rx.crawler.service.ConfigureScriptExecutor;
import org.rx.crawler.util.ProcessUtil;
import org.rx.exception.TraceHandler;
import org.rx.exception.InvalidException;
import org.rx.bean.Tuple;
import org.rx.crawler.service.CookieContainer;
import org.rx.core.*;
import org.rx.io.Files;
import org.rx.util.Lazy;
import org.rx.util.function.BiFunc;

import java.awt.*;
import java.awt.event.InputEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.rx.core.Sys.*;
import static org.rx.core.Extends.*;

/**
 * 不缓存DriverService，奔溃后自恢复
 */
@Slf4j
public final class WebBrowser extends Disposable implements Browser, EventPublisher<WebBrowser> {
    //region static
    static final String RESOURCE_JS_PATH = "/bot/root.js";
    static final Lazy<ChromeDriverService> chromeServiceLazy = new Lazy<>(() -> new ChromeDriverService.Builder().withSilent(true).withVerbose(false).build());
    static final AtomicInteger chromeIdCounter = new AtomicInteger();
    static final ConcurrentHashMap<RemoteWebDriver, Tuple<DateTime, Linq<Long>>> iePidMap = new ConcurrentHashMap<>();

    static void killIe(RemoteWebDriver driver) {
        quietly(() -> {
            Tuple<DateTime, Linq<Long>> tuple = iePidMap.remove(driver);
            if (tuple != null) {
                for (Long pid : tuple.right) {
                    ProcessUtil.killProcess(pid);
                }
            }
        });
    }

    static Linq<Long> getIePids() {
        return ProcessUtil.getProcesses(BrowserType.IE.getProcessName(), BrowserType.IE.getDriverName()).select(ProcessHandle::pid);
    }
    //endregion

    //region init
    public final Delegate<WebBrowser, EventArgs> onNavigating = Delegate.create(), onNavigated = Delegate.create();
    final WebBrowserConfig config;
    final CookieContainer cookieContainer;
    final ConfigureScriptExecutor configureScriptExecutor;
    final Set<String> injectedScript = new HashSet<>();
    //设置后才会自动装载cookie
    @Getter
    @Setter
    private String cookieRegion;
    @Getter
    @Setter
    private long waitMillis = 500;
    private DriverService driverService;
    private RemoteWebDriver driver;
    private volatile String navigatedUrl, navigatedSelector;

    @Override
    public BrowserType getType() {
        if (driver instanceof InternetExplorerDriver) {
            return BrowserType.IE;
        }
        return BrowserType.CHROME;
    }

    public String getCurrentUrl() {
        return driver.getCurrentUrl();
    }

    public String getCurrentHandle() {
        return driver.getWindowHandle();
    }

    public Rectangle getWindowRectangle() {
        checkNotClosed();

        WebDriver.Window window = driver.manage().window();
        return new Rectangle(window.getPosition(), window.getSize());
    }

    public void setWindowRectangle(@NonNull Rectangle rectangle) {
        checkNotClosed();

        WebDriver.Window window = driver.manage().window();
        window.setPosition(rectangle.getPoint());
        window.setSize(rectangle.getDimension());
    }

    @SneakyThrows
    public WebBrowser(@NonNull WebBrowserConfig config, @NonNull BrowserType type) {
        this.config = config;
        this.cookieContainer = config.getCookieContainer();
        this.configureScriptExecutor = Reflects.newInstance(Class.forName(config.getConfigureScriptExecutorType()), this);
        Tuple<DriverService, RemoteWebDriver> tuple = createDriver(type);
        driverService = tuple.left;
        driver = tuple.right;
    }

    //共享一个ChromeDriverService会出问题
    @SneakyThrows
    private Tuple<DriverService, RemoteWebDriver> createDriver(BrowserType type) {
        DriverService driverService;
        RemoteWebDriver driver;
        switch (type) {
            case IE: {
                driverService = InternetExplorerDriverService.createDefaultService();

                InternetExplorerOptions opt = fill(new InternetExplorerOptions());
                opt.withInitialBrowserUrl(BLANK_URL)
                        .ignoreZoomSettings()
//                            .introduceFlakinessByIgnoringSecurityDomains()  //NoSuchWindowException
                        .setUnhandledPromptBehaviour(UnexpectedAlertBehaviour.ACCEPT)
//                        .setCapability(CapabilityType.APPLICATION_NAME, "rxBrowser")
                ;

                Linq<Long> iePids_before = getIePids();
                driver = new InternetExplorerDriver((InternetExplorerDriverService) driverService, opt);
                sleep(500);
                iePidMap.put(driver, Tuple.of(DateTime.now(), getIePids().except(iePids_before)));
            }
            break;
            default:
                driverService = chromeServiceLazy.getValue();

                ChromeOptions opt = (ChromeOptions) fill(new ChromeOptions())
//                        .setHeadless(false)
                        .setAcceptInsecureCerts(true)
                        .setUnhandledPromptBehaviour(UnexpectedAlertBehaviour.ACCEPT)
                        .setPageLoadStrategy(PageLoadStrategy.NORMAL);

                Map<String, Object> chromePrefs = new HashMap<>();
                if (config.getDownloadPath() != null) {
                    Files.createDirectory(config.getDownloadPath());
                    chromePrefs.put("download.default_directory", config.getDownloadPath());
                }
                chromePrefs.put("profile.default_content_settings.popups", 0);
                chromePrefs.put("pdfjs.disabled", true);
                opt.setExperimentalOption("prefs", chromePrefs);

//                opt.addArguments("user-agent=Mozilla/5.0 (iPad; CPU iPhone OS 8_3 like Mac OS X) AppleWebKit/600.1.4 (KHTML, like Gecko) FxiOS/1.0 Mobile/12F69 Safari/600.1.4");

                opt.addArguments("no-first-run", "homepage=https://f-li.cn/",
                        "disable-infobars", "disable-web-security", "ignore-certificate-errors", "allow-running-insecure-content",
                        "disable-java", "disable-plugins", "disable-plugins-discovery", "disable-extensions",
                        "disable-desktop-notifications", "disable-speech-input", "disable-translate", "safebrowsing-disable-download-protection", "no-pings",
                        "no-sandbox", "autoplay-policy=Document user activation is required");

                if (!Strings.isEmpty(config.getDiskDataPath())) {
                    int id = chromeIdCounter.getAndIncrement();
                    String dataDir = String.format(config.getDiskDataPath(), id);
                    Files.createDirectory(dataDir);
                    opt.addArguments("user-data-dir=" + dataDir, "restore-last-session");
                }
                opt.addArguments("--remote-allow-origins=*");
                //disk-cache-dir,disk-cache-size
                driver = new ChromeDriver((ChromeDriverService) driverService, opt);
                break;
        }
        WebDriver.Options manage = driver.manage();
        manage.timeouts().pageLoadTimeout(config.getPageLoadTimeoutSeconds(), TimeUnit.SECONDS);
        manage.timeouts().setScriptTimeout(config.getPageLoadTimeoutSeconds(), TimeUnit.SECONDS);
        Rectangle rectangle = config.getWindowRectangle();
        if (rectangle != null) {
            manage.window().setPosition(rectangle.getPoint());
            manage.window().setSize(rectangle.getDimension());
        }
        return Tuple.of(driverService, driver);
    }

    private <T extends MutableCapabilities> T fill(T opt) {
//        opt.setCapability(CapabilityType.SUPPORTS_APPLICATION_CACHE, true);
//        opt.setCapability(CapabilityType.SUPPORTS_ALERTS, false);
        opt.setCapability(CapabilityType.UNHANDLED_PROMPT_BEHAVIOUR, UnexpectedAlertBehaviour.ACCEPT);
//        opt.setCapability(CapabilityType.UNEXPECTED_ALERT_BEHAVIOUR, UnexpectedAlertBehaviour.ACCEPT);
        return opt;
    }

    @Override
    protected void freeObjects() {
        driver.quit();
        driverService.stop();
        killIe(driver);
    }
    //endregion

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
        boolean isIe = getType() == BrowserType.IE;
        try {
            if (isIe) {
                exchangeDriver(url, true);
            }
            if (cookieRegion != null) {
                //不要变更url
                cookieContainer.loadTo(this, CookieContainer.buildRegionUrl(url, cookieRegion));
            }
            raiseEvent(onNavigating, EventArgs.EMPTY);

            driver.get(url);
            navigatedUrl = url;
            navigatedSelector = locatorSelector;

            if (locatorSelector != null) {
                waitElementLocated(locatorSelector, timeoutSeconds, checkComplete);
            }
            raiseEvent(onNavigated, EventArgs.EMPTY);
            if (cookieRegion != null) {
                try {
                    saveCookies(true);
                } catch (TimeoutException e) {
                    log.warn("ignore cookieDomain reset {}", e.getMessage());
                }
            }
        } catch (WebDriverException e) {
            if (isIe) {
                exchangeDriver(url, false);
                sleep(waitMillis);
            }
            throw e;
        } catch (TimeoutException e) {
            TraceHandler.INSTANCE.log("waitElementLocated fail, url={} selector={}|{}", url, locatorSelector, timeoutSeconds, e);
            throw e;
        } catch (Exception e) {
            throw new InvalidException("NavigateUrl {} fail", url, e);
        } finally {
            navigatedSelector = null;
        }
    }

    private synchronized void exchangeDriver(String url, boolean isCheck) {
        Tuple<DriverService, RemoteWebDriver> temp = Tuple.of(driverService, driver), exchange;
        boolean doIt = true;
        if (isCheck) {
            Tuple<DateTime, Linq<Long>> tuple = iePidMap.get(temp.right);
            doIt = tuple == null || DateTime.now().subtract(tuple.left).getTotalMinutes() > 30;
        }
        if (!doIt) {
            return;
        }

        log.warn("exchangeDriver for {}", url);
        quietly(() -> {
            temp.right.quit();
            temp.left.stop();
            killIe(temp.right);
        });
        sleep(800);
        exchange = createDriver(getType());
        driverService = exchange.left;
        driver = exchange.right;
    }

    @Override
    public synchronized String saveCookies(boolean reset) throws TimeoutException {
        String u = currentCookieUrl();
        String rawCookie = cookieContainer.syncFrom(this, u);
        if (reset) {
            driver.get(u);
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
            cookieContainer.clearCookie(this, u);
            return;
        }

        cookieContainer.clear(u);
        cookieContainer.loadTo(this, u);
    }

    @Override
    public void setRawCookie(String rawCookie) {
        cookieContainer.save(currentCookieUrl(), rawCookie);
    }

    @Override
    public String getRawCookie() {
        return cookieContainer.get(currentCookieUrl());
    }

    private String currentCookieUrl() {
        String u = getCurrentUrl();
        return cookieRegion != null ? CookieContainer.buildRegionUrl(u, cookieRegion) : u;
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

        return findElements(selector, false).select(WebElement::getText);
    }

    @Override
    public String elementVal(String selector) {
        return ifNull(elementsVal(selector).firstOrDefault(), "");
    }

    @Override
    public Linq<String> elementsVal(String selector) {
        return elementsAttr(selector, "value");
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
        Linq<WebElement> elements = findElements(selector, false);
        if (attrVal != null) {
            for (WebElement elm : elements) {
                executeScript("arguments[0].setAttribute(arguments[1], arguments[2]);", elm, attrName, attrVal);
            }
            return Linq.from();
        }
        return elements.select(p -> p.getAttribute(attrName));
    }

    @Override
    public void elementClick(String selector) {
        elementClick(selector, false);
    }

    @Override
    public void elementClick(String selector, boolean waitElementLocated) {
        elementClick(selector, waitElementLocated, null);
    }

    @SneakyThrows
    public synchronized void elementClick(String selector, boolean waitElementLocated, Function<WebElement, Point> botClick) {
        checkNotClosed();

        WebElement element;
        if (waitElementLocated) {
            element = waitElementLocated(selector, config.getFindElementTimeoutSeconds(), null).first();
        } else {
            element = findElement(selector, false);
        }

        boolean clicked = false;
        try {
            if (element != null) {
                element.click();
                clicked = true;
            }
        } catch (WebDriverException e) {
            if (botClick != null) {
                Point point = botClick.apply(element);
                focus();
                mouseLeftClick(point.x, point.y);
                clicked = true;
                return;
            }
            if (e instanceof ElementNotInteractableException) {
                log.warn("script reClick {}", e.getMessage());
                return;
            }
            log.warn("script reClick", e);
        } finally {
            if (!clicked && getType() == BrowserType.CHROME) {
                executeScript("_rx.query(arguments[0]).click();", selector);
            }
        }
    }

    @SneakyThrows
    private synchronized void mouseLeftClick(int x, int y) {
        Robot bot = new Robot();
        bot.mouseMove(x, y);
        bot.delay(5);
        bot.mousePress(InputEvent.BUTTON1_MASK);
        bot.delay(5);
        bot.mouseRelease(InputEvent.BUTTON1_MASK);
        bot.delay(10);
    }

    @Override
    public void elementPress(String selector, String keys) {
        elementPress(selector, keys, false);
    }

    @SneakyThrows
    @Override
    public void elementPress(String selector, String keys, boolean waitElementLocated) {
        checkNotClosed();

        WebElement element;
        if (waitElementLocated) {
            element = waitElementLocated(selector, config.getFindElementTimeoutSeconds(), null).first();
        } else {
            element = findElement(selector, true);
        }
        element.clear();
        element.sendKeys(keys);
    }

    @Override
    public void waitElementLocated(String selector) throws TimeoutException {
        waitElementLocated(selector, config.getFindElementTimeoutSeconds());
    }

    @Override
    public void waitElementLocated(String selector, int timeoutSeconds) throws TimeoutException {
        waitElementLocated(selector, timeoutSeconds, null);
    }

    private Linq<WebElement> waitElementLocated(String selector, int timeoutSeconds, Predicate<Integer> checkComplete) throws TimeoutException {
        Linq<WebElement> q = createWait(timeoutSeconds).await(new BiFunc<>() {
            @Override
            public Linq<WebElement> invoke(FluentWait s) throws Throwable {
                Linq<WebElement> elements = WebBrowser.this.findElements(selector, false);
                if (elements.any()) {
                    log.debug("Wait {} located ok", selector);
                    return elements;
                }

                elements = WebBrowser.this.findElements(selector, false);
                if (elements.any()) {
                    log.debug("Wait {} located ok", selector);
                    return elements;
                }
                if (checkComplete != null && checkComplete.test(s.getEvaluatedCount())) {
                    return elements;
                }
                return null;
            }

            @Override
            public String toString() {
                return String.format("cssSelector(%s)", selector);
            }
        });
        if (q == null || !q.any()) {
            throw new TimeoutException(String.format("No such elements '%s'", selector));
        }
        return q;
    }

    private WebElement findElement(String selector, boolean throwOnEmpty) {
        checkNotClosed();

        Linq<WebElement> elements = findElements(selector, throwOnEmpty);
        if (!elements.any()) {
            if (throwOnEmpty) {
                throw new InvalidException("Element {} not found", selector);
            }
            return null;
        }
        return elements.first();
    }

    private Linq<WebElement> findElements(@NonNull String selector, boolean throwOnEmpty) {
        checkNotClosed();

        By by;
        if (Strings.startsWith(selector, "/")) {
            by = By.xpath(selector);
        } else {
            by = By.cssSelector(selector);
        }

        try {
            return Linq.from(driver.findElements(by));
        } catch (NoSuchElementException e) {
            if (throwOnEmpty) {
                throw e;
            }
            return Linq.from();
        }
    }

    public byte[] screenshotAsBytes(String selector) {
        return findElement(selector, true).getScreenshotAs(OutputType.BYTES);
    }

    //region script
    @Override
    public synchronized void injectScript(@NonNull String script) {
        checkNotClosed();
        if (injectedScript.contains(script)) {
            return;
        }

        driver.executeScript(script);
        injectedScript.add(script);
    }

    @Override
    public synchronized <T> T executeScript(@NonNull String script, Object... args) {
        checkNotClosed();

        if (getType() != BrowserType.IE) {
            String rootJs = Cache.<String, String>getInstance(MemoryCache.class).get(cacheKey("injectRootJs"), k -> Browser.readResourceJs(RESOURCE_JS_PATH));
//            log.warn("injectScript:\n{}\n", rootJs);
            injectScript(rootJs);
        }
        try {
            return (T) driver.executeScript(script, args);
        } catch (JavascriptException e) {
            throw new JavascriptException(String.format("ScriptContent:\n%s\n\n%s", script, e.getMessage()), e);
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
    //endregion

    //region methods
    public synchronized void focus() {
        checkNotClosed();

        setWindowRectangle(getWindowRectangle());
    }

    public synchronized void maximize() {
        checkNotClosed();

        WebDriver.Window window = driver.manage().window();
        window.maximize();
    }

    public synchronized void normalize() {
        checkNotClosed();

        Rectangle rect = config.getWindowRectangle();
        if (rect != null) {
            setWindowRectangle(config.getWindowRectangle());
        }
    }

    @Override
    public synchronized void nativeGet(String url) {
        driver.get(url);
    }

    public synchronized void switchToDefault() {
        checkNotClosed();
        driver.switchTo().defaultContent();
    }

    public synchronized void switchToFrame(String selector) {
        checkNotClosed();

        WebElement element = findElement(selector, true);
        driver.switchTo().frame(element);
    }

    public synchronized String openTab() {
        checkNotClosed();

        driver.executeScript("window.open('about:blank','_blank')");
        return Linq.from(driver.getWindowHandles()).last();
    }

    public synchronized void switchTab(@NonNull String winHandle) {
        checkNotClosed();

        driver.switchTo().window(winHandle);
    }

    public synchronized void closeTab(@NonNull String winHandle) {
        checkNotClosed();

        String current = driver.getWindowHandle();
        boolean isSelf = current.equals(winHandle);
        if (!isSelf) {
            switchTab(winHandle);
        }
        driver.close();
        if (isSelf) {
            current = Linq.from(driver.getWindowHandles()).first();
        }
        switchTab(current);
    }
    //endregion
}
