package org.rx.crawler.service.impl;

import lombok.Data;
import org.openqa.selenium.Rectangle;
import org.rx.crawler.service.CookieContainer;

@Data
public class WebBrowserConfig {
    private long waitMillis = 500;
    private int pageLoadTimeoutSeconds = 30;
    private int findElementTimeoutSeconds = 6;
    private String diskDataPath;
    private String downloadPath;
    private Rectangle windowRectangle;
    private CookieContainer cookieContainer;
    private String configureScriptExecutorType;
}
