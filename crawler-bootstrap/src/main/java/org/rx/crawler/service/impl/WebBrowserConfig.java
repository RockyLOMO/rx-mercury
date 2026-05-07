package org.rx.crawler.service.impl;

import lombok.Data;
import org.openqa.selenium.Rectangle;
import org.rx.crawler.service.CookieContainer;

@Data
public class WebBrowserConfig {
    private long waitMillis = 500;
    private int pageLoadTimeoutSeconds = 30;
    private int findElementTimeoutSeconds = 6;
    private boolean headless = true;
    private String diskDataPath;
    private String downloadPath;
    private Rectangle windowRectangle;
    private CookieContainer cookieContainer;
    private String configureScriptExecutorType;
    private boolean fingerprintEnabled = false;
    private String fingerprintScriptPath = "/bot/chrome-fingerprint.js";
    private String fingerprintStealthScriptPath = "/static/js/stealth.min.js";
    private String fingerprintCheckUrl = "https://bot.sannysoft.com/";
    private boolean fingerprintHeadless = false;
    private boolean fingerprintDiagnostics = false;
}
