package org.rx.crawler.task.jd;

import lombok.Data;

@Data
public class JdUnionConfig {
    private String profileName = "common";
    private boolean fingerprintEnabled = true;
    private boolean headless = false;

    private boolean preflightEnabled = true;
    private String preflightUrl = "https://bot.sannysoft.com/";
    private boolean preflightStrict = true;
    private int preflightCacheMinutes = 30;
    private boolean forcePreflight = true;

    private String overviewUrl = "https://union.jd.com/overview";
    private String loginCheckUrl = "https://union.jd.com/overview";
    private String workbenchUrl = "https://union.jd.com/proManager/index?pageNo=1";
    private String loginUrlPrefix = "https://union.jd.com/index?returnUrl=";
    private String defaultMediaType = "导购媒体推广";
    private String defaultMediaName = "微信";

    private int pageTimeoutSeconds = 60;
    private int initialPageTimeoutSeconds = 60;
    private int stepDelayMillis = 1200;
    private boolean keepBrowserOpenOnLoginRequired = true;
    private int keepBrowserOpenSecondsOnLoginRequired = 180;
    private int loginWaitSeconds = 180;
    private String defaultOutputPath = "D:/app-crawler/data/jd-union/output.jsonl";
    private boolean debugEnabled = false;
    private String debugOutputDir = "D:/app-crawler/data/jd-union/debug";
}
