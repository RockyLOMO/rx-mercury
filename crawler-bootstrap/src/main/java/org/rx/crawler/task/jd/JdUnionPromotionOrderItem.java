package org.rx.crawler.task.jd;

import lombok.Data;

import java.io.Serializable;

@Data
public class JdUnionPromotionOrderItem implements Serializable {
    private static final long serialVersionUID = -5338651386297968923L;

    private String orderStatus;
    private String time;
    private String estimatedBillingAmount;
    private String estimatedCommission;
    private String commissionRate;
    private String shareRate;
    private String actualBillingAmount;
    private String actualCommission;
    private String quantity;
    private String promotionInfo;
    private String orderType;
}
