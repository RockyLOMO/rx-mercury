package org.rx.crawler.task.tb;

import lombok.Data;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.jd.JdUnionProductInfoDto;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Data
public class TbPromotionUrlResult implements Serializable {
    private static final long serialVersionUID = 1741692125455073382L;

    private CustomCrawlStatus status;
    private String taskType;
    private String productInfoText;
    private String adSiteName;
    private String mediaName;
    private String profileName;
    private JdUnionProductInfoDto productInfo;
    private String promotionUrl;
    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
}
