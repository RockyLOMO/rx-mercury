package org.rx.crawler.task.common;

import lombok.Data;

@Data
public class ChromeProfileConfig {
    private String profileBasePath = "./data/chrome";
    private String defaultProfileName = "common";
    private boolean closeBrowserAfterTask = false;
    private int maxActive = 3;
    private int minIdle = 1;
    private boolean preWarm = true;
}
