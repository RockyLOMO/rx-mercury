package org.rx.crawler.task.common;

import lombok.Data;

@Data
public class ChromeProfileConfig {
    private String profileBasePath = "./data/chrome";
    private String defaultProfileName = "common";
}
