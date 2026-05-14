package org.rx.crawler.task.common;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Data
public class PromotionUrlResult implements Serializable {
    private static final long serialVersionUID = 6813060541670472936L;

    private CustomCrawlStatus status;
    private String taskType;
    private String keyword;
    private String adSiteName;
    private String mediaName;
    private String profileName;
    private ProductInfoDto productInfo;
    private String promotionUrl;
    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
}
