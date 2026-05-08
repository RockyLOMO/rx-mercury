package org.rx.crawler.task.jd;

import lombok.Data;
import org.rx.crawler.task.common.CustomCrawlStatus;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Data
public class JdUnionPromotionResult implements Serializable {
    private static final long serialVersionUID = 8789408467263205601L;

    private CustomCrawlStatus status;
    private String taskType;
    private String skuId;
    private String adSiteName;
    private String mediaType;
    private String mediaName;
    private String profileName;
    private String promotionUrl;
    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
}
