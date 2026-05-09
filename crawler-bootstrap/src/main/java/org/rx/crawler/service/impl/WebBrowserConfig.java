package org.rx.crawler.service.impl;

import lombok.Data;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.net.http.HttpClientCookieJar;

@Data
public class WebBrowserConfig {
    private long waitMillis = 500;
    private int pageLoadTimeoutSeconds = 30;
    private int findElementTimeoutSeconds = 6;
    private boolean headless = true;
    private String profileDataPath;
    private String downloadPath;
    private BrowserWindowRect windowRectangle;
    private String configureScriptExecutorType;
    private boolean fingerprintEnabled = false;
    private String fingerprintScriptPath = "/bot/chrome-fingerprint.js";
    private String fingerprintStealthScriptPath = "/static/js/stealth.min.js";
    private String fingerprintCheckUrl = "https://bot.sannysoft.com/";
    private boolean fingerprintHeadless = false;
    private boolean fingerprintDiagnostics = false;
    private String playwrightChannel = "chrome";
    private String playwrightExecutablePath;
    private String locale = "zh-CN";
    private String timezoneId = "Asia/Shanghai";
    private String userAgent;
    private boolean blockResourceEnabled = false;
    private boolean humanInputEnabled = true;
    private int humanActionMinDelayMillis = 180;
    private int humanActionMaxDelayMillis = 650;
    private int mouseMoveMinSteps = 24;
    private int mouseMoveMaxSteps = 56;
    private int typingMinDelayMillis = 90;
    private int typingMaxDelayMillis = 260;
    private HttpClientCookieJar cookieJar;
}
