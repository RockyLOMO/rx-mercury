package org.rx.crawler.task.common;

public enum CustomCrawlStatus {
    SUCCESS,
    LOGIN_REQUIRED,
    BROWSER_FINGERPRINT_CHECK_FAILED,
    NOT_FOUND,
    MULTIPLE_MATCHED,
    PAGE_CHANGED,
    TIMEOUT,
    FAILED
}
