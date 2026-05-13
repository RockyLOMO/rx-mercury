package org.rx.crawler.task.tb;

import lombok.Data;

@Data
public class TbPromotionConfig {
    private String profileName = "common";
    private boolean fingerprintEnabled = true;
    private boolean headless = false;

    private boolean preflightEnabled = true;
    private String preflightUrl = "https://bot.sannysoft.com/";
    private boolean preflightStrict = true;
    private int preflightCacheMinutes = 30;
    private boolean forcePreflight = true;

    private String homeUrl = "https://pub.alimama.com/portal/v2/home/plus/index.htm";
    private String orderUrl = "https://pub.alimama.com/portal/v2/effect/order/overviewOrder/page/index.htm";
    private String loginUrlPrefix = "https://login.taobao.com/havanaone/login/login.htm";

    private int pageTimeoutSeconds = 60;
    private int initialPageTimeoutSeconds = 180;
    private int stepDelayMillis = 1200;
    private boolean keepBrowserOpenOnLoginRequired = true;
    private int keepBrowserOpenSecondsOnLoginRequired = 180;
    private int loginWaitSeconds = 180;
    private String defaultOutputPath = "D:/app-crawler/data/tb/output.jsonl";
    private String debugOutputDir = "D:/app-crawler/data/tb/debug";
}
