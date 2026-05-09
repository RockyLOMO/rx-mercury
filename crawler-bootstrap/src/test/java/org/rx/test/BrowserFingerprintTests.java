package org.rx.test;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.core.Extends;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.crawler.service.impl.ApiConfigureScriptExecutor;
import org.rx.crawler.service.impl.MemoryCookieContainer;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.rx.util.BeanMapper;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.rx.core.Extends.tryClose;

public class BrowserFingerprintTests {
    @Test
    public void chromeFingerprintResourcesExist() {
        assertResourceExists("/static/js/stealth.min.js");
        assertResourceExists("/bot/chrome-fingerprint.js");
    }

    @SneakyThrows
    @Test
    public void sannysoftAuthorizedDiagnostics() {
        assumeTrue(Boolean.parseBoolean(System.getProperty("fingerprint.integration", "false")));

        AppConfig appConfig = new AppConfig();
        System.setProperty("app.browser.fingerprintEnabled", "true");
        System.setProperty("app.browser.fingerprintDiagnostics", "true");

        WebBrowserConfig config = BeanMapper.DEFAULT.map(appConfig.getBrowser(), WebBrowserConfig.class);
        config.setHeadless(false);
        config.setFingerprintEnabled(true);
        config.setFingerprintDiagnostics(true);
        config.setFingerprintHeadless(Boolean.parseBoolean(System.getProperty("app.browser.fingerprintHeadless", "false")));
        config.setCookieContainer(new MemoryCookieContainer());
        config.setConfigureScriptExecutorType(ApiConfigureScriptExecutor.class.getName());
        config.setDownloadPath(System.getProperty("app.browser.downloadPath", "D:/app-crawler/temp/"));
        config.setProfileDataPath(System.getProperty("app.browser.profileDataPath", "D:/app-crawler/data/chrome/profile"));
        config.setWindowRectangle(new BrowserWindowRect(0, 0, 900, 1365));

        WebBrowser browser = new WebBrowser(config, BrowserType.CHROME);
        try {
            String checkUrl = System.getProperty("app.browser.fingerprintCheckUrl", config.getFingerprintCheckUrl());
            browser.navigateUrl(checkUrl, "body", 60);
            Extends.sleep(5000);

            Map<String, Object> probe = browser.executeScript("return {" +
                    "webdriver: navigator.webdriver," +
                    "userAgent: navigator.userAgent," +
                    "plugins: navigator.plugins ? navigator.plugins.length : 0," +
                    "languages: navigator.languages ? Array.prototype.slice.call(navigator.languages).join(',') : ''," +
                    "chrome: !!window.chrome," +
                    "webglVendor: (function(){try{var c=document.createElement('canvas').getContext('webgl');var e=c.getExtension('WEBGL_debug_renderer_info');return c.getParameter(e.UNMASKED_VENDOR_WEBGL);}catch(e){return '';}})()," +
                    "webglRenderer: (function(){try{var c=document.createElement('canvas').getContext('webgl');var e=c.getExtension('WEBGL_debug_renderer_info');return c.getParameter(e.UNMASKED_RENDERER_WEBGL);}catch(e){return '';}})()" +
                    "};");
            String report = browser.executeScript("return Array.prototype.slice.call(document.querySelectorAll('tr')).map(function(tr){return tr.innerText;}).join('\\n') || document.body.innerText;");
            System.out.println("Sannysoft probe: " + probe);
            System.out.println("Sannysoft report:\n" + report);

            Object webdriver = probe.get("webdriver");
            assertTrue(webdriver == null || Boolean.FALSE.equals(webdriver), "navigator.webdriver should be hidden");
            assertFalse(String.valueOf(probe.get("userAgent")).contains("HeadlessChrome"), "userAgent should not expose HeadlessChrome");
            assertTrue(Boolean.TRUE.equals(probe.get("chrome")), "window.chrome should exist");
            assertTrue(((Number) probe.get("plugins")).intValue() > 0, "navigator.plugins should not be empty");
        } finally {
            tryClose(browser);
        }
    }

    private void assertResourceExists(String path) {
        try (InputStream in = BrowserFingerprintTests.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
        } catch (Exception e) {
            throw new AssertionError(path, e);
        }
    }
}
