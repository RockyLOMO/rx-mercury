package org.rx.crawler.task.jd;

import lombok.Data;

@Data
public class JdUnionConfig {
    private String profileName = "common";
    private boolean fingerprintEnabled = true;
    private boolean headless = false;

    private boolean preflightEnabled = true;
    private String preflightUrl = "https://bot.sannysoft.com/";
    private boolean preflightStrict = false;
    private int preflightCacheMinutes = 30;
    private boolean forcePreflight = true;

    private String loginCheckUrl = "https://union.jd.com/proManager/shopPromotion";
    private String workbenchUrl = "https://union.jd.com/proManager/index?pageNo=1";
    private String loginUrlPrefix = "https://union.jd.com/index?returnUrl=";
    private String defaultMediaType = "导购媒体推广";
    private String defaultMediaName = "微信";

    private int pageTimeoutSeconds = 60;
    private int stepDelayMillis = 1200;
    private boolean keepBrowserOpenOnLoginRequired = true;
    private int keepBrowserOpenSecondsOnLoginRequired = 300;
    private int loginWaitSeconds = 300;
    private String defaultOutputPath = "D:/app-crawler/data/jd-union/output.jsonl";
}
