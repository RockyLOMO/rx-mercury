package org.rx.test;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Rectangle;
import org.rx.core.Reflects;
import org.rx.core.RxConfig;
import org.rx.core.YamlConfiguration;
import org.rx.crawler.Application;
import org.rx.crawler.BrowserType;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.MemoryCookieContainer;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.util.BeanMapper;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import static org.rx.core.Extends.sleep;

@Slf4j
public class DevTest {
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
}
