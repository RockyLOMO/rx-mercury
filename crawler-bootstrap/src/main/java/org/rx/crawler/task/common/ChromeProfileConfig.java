package org.rx.crawler.task.common;

import lombok.Data;

@Data
public class ChromeProfileConfig {
    private String profileBasePath = defaultBasePath();
    private String defaultProfileName = "common";

    private static String defaultBasePath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "D:/app-crawler/data/chrome";
        }
        return "/app-crawler/data/chrome";
    }
}
