package org.rx.crawler.task.tb;

import lombok.Data;
import org.rx.crawler.task.common.CustomCrawlStatus;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class TbPromotionOrdersResult implements Serializable {
    private static final long serialVersionUID = -5335062371206213565L;

    private CustomCrawlStatus status;
    private String taskType;
    private String startTime;
    private String endTime;
    private String profileName;
    private String currentUrl;
    private boolean loginRequired;
    private boolean fingerprintPassed;
    private String message;
    private Date createdAt = new Date();
    private List<TbPromotionOrderItem> orders = new ArrayList<TbPromotionOrderItem>();
    private Map<String, Object> diagnostics = new HashMap<String, Object>();
}
