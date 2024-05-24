package org.rx.test;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.ie.InternetExplorerOptions;
import org.rx.crawler.BrowserType;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.BrowserPool;
import org.rx.crawler.service.impl.RedisCookieContainer;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.util.BeanMapper;

import static org.rx.core.Extends.sleep;

@Slf4j
public class DevTest {
    BrowserPool pool;

    public WebBrowser init(BrowserType type) {
        AppConfig config = new AppConfig();
        System.setProperty("webdriver.chrome.driver", config.getChromeDriver());
        System.setProperty("webdriver.gecko.driver", config.getFireFoxDriver());
        System.setProperty("webdriver.ie.driver", config.getIeDriver());

        WebBrowserConfig conf = BeanMapper.DEFAULT.map(config.getBrowser(), WebBrowserConfig.class);
        conf.setDiskDataPath(conf.getDiskDataPath());
        conf.setDownloadPath(conf.getDownloadPath());
//        conf.setWindowRectangle(new Rectangle(0, 0, 600, 800));
//        conf.setCookieContainer(new RedisCookieContainer());
        conf.setConfigureScriptExecutorType(conf.getConfigureScriptExecutorType());
        log.info("loadConf {}", conf);
        return new WebBrowser(conf, type);
    }

    @SneakyThrows
    @Test
    public void chrome() {
        WebBrowser browser = init(BrowserType.CHROME);

        String cs1 = ".icon-qrcode";
        browser.navigateUrl("https://login.taobao.com/member/login.jhtml?style=mini&newMini2=true&from=alimama&redirectURL=http:%2F%2Flogin.taobao.com%2Fmember%2Ftaobaoke%2Flogin.htm%3Fis_login%3d1&full_redirect=true&disableQuickLogin=false", cs1);
        browser.elementClick(cs1);
        sleep(1000);
        byte[] bytes = browser.screenshotAsBytes(".qrcode-img");
        System.out.println(bytes.length);

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void firefox() {
        WebBrowser browser = init(BrowserType.FIRE_FOX);
        browser.navigateUrl("https://union.jd.com");

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void changeTab() {
        WebBrowser caller = init(BrowserType.CHROME);
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
