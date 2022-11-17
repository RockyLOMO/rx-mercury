package org.rx.test;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Rectangle;
import org.rx.crawler.BrowserType;
import org.rx.crawler.service.BrowserPool;
import org.rx.crawler.service.impl.MemoryCookieContainer;
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
        conf.setCookieContainer(new MemoryCookieContainer());
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
