package org.rx.crawler.task.common;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class CrawlEntryResult {
    private boolean passed;
    private CustomCrawlStatus status;
    private String message = "";
    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
}
