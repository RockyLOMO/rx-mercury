package org.rx.crawler.task.common;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class LoginKeepAliveResult {
    private String platform;
    private String taskType;
    private String profileName;
    private String checkedUrl;
    private String currentUrl;
    private boolean healthy;
    private boolean loginRequired;
    private CustomCrawlStatus status;
    private String message = "";
    private LocalDateTime checkedAt = LocalDateTime.now();
    private Map<String, Object> diagnostics = new LinkedHashMap<String, Object>();
}
