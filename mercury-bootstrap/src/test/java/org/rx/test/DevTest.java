package org.rx.test;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.ie.InternetExplorerOptions;
import org.rx.crawler.BrowserType;
import org.rx.crawler.service.BrowserPool;
import org.rx.crawler.service.impl.RedisCookieContainer;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;

import static org.rx.core.Extends.sleep;

@Slf4j
public class DevTest {
    BrowserPool pool;

    public WebBrowser init() {
        String baseDir = "/app-crawler/";
        System.setProperty("webdriver.chrome.driver", String.format("%s/driver/chromedriver.exe", baseDir));
        System.setProperty("webdriver.ie.driver", String.format("%s/driver/IEDriverServer.exe", baseDir));

        WebBrowserConfig conf = new WebBrowserConfig();
        conf.setDiskDataPath("/app-crawler/data%s/");
//        conf.setDownloadPath("/app-crawler/download/");
        conf.setWindowRectangle(new Rectangle(0, 0, 600, 800));
        conf.setCookieContainer(new RedisCookieContainer());
        conf.setConfigureScriptExecutorType("org.rx.crawler.service.impl.ApiConfigureScriptExecutor");
        log.info("loadConf {}", conf);
        return new WebBrowser(conf, BrowserType.CHROME);
    }

    @SneakyThrows
    @Test
    public void dev() {
        WebBrowser browser = init();

        String cs1 = ".icon-qrcode";
        browser.navigateUrl("https://login.taobao.com/member/login.jhtml?style=mini&newMini2=true&from=alimama&redirectURL=http:%2F%2Flogin.taobao.com%2Fmember%2Ftaobaoke%2Flogin.htm%3Fis_login%3d1&full_redirect=true&disableQuickLogin=false", cs1);
        browser.elementClick(cs1);
        sleep(1000);
        byte[] bytes = browser.screenshotAsBytes(".qrcode-img");
        System.out.println(bytes.length);
    }

    @SneakyThrows
    @Test
    public void webLogin() {
        String baseDir = "/app-crawler/";
        System.setProperty("webdriver.chrome.driver", String.format("%s/driver/chromedriver.exe", baseDir));
        System.setProperty("webdriver.ie.driver", String.format("%s/driver/IEDriverServer.exe", baseDir));
        String url = "https://login.taobao.com/member/login.jhtml?style=mini&newMini2=true&from=alimama&redirectURL=http:%2F%2Flogin.taobao.com%2Fmember%2Ftaobaoke%2Flogin.htm%3Fis_login%3d1&full_redirect=true&disableQuickLogin=false";
        InternetExplorerOptions opt = new InternetExplorerOptions();
        opt.withInitialBrowserUrl("about:blank");
        InternetExplorerDriver driver = new InternetExplorerDriver(opt);
        driver.get(url);
        Thread.sleep(3000);
        By locator = By.id("J_SubmitQuick");
        while (!driver.getCurrentUrl().contains("alimama.com")) {
            driver.findElement(locator).click();
            System.out.println("click...");
            Thread.sleep(1000);
        }

        System.out.println("url: " + driver.getCurrentUrl());
        for (org.openqa.selenium.Cookie cookie : driver.manage().getCookies()) {
            System.out.println(cookie.getName());
        }
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void changeTab() {
        WebBrowser caller = init();
        String currentHandle = caller.getCurrentHandle();
        System.out.println(currentHandle);

        String handle = caller.openTab();
        System.out.println(handle);
        Thread.sleep(2000);

        caller.openTab();
        System.out.println(handle);
        Thread.sleep(2000);

        caller.switchTab(handle);
        System.out.println("switch");
        Thread.sleep(2000);

        caller.closeTab(handle);
        System.out.println("close");
    }
}
