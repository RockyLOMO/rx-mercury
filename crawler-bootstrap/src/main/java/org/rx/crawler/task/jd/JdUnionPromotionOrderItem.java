package org.rx.crawler.task.jd;

import lombok.Data;

import java.io.Serializable;

@Data
public class JdUnionPromotionOrderItem implements Serializable {
    private static final long serialVersionUID = -5338651386297968923L;

    private String productName;
    private String productLink;
    private String productPrice;
    private String storeName;
    private String orderNo;
    private String mainOrderNo;
    private String orderStatus;
    private String time;
    private String orderTime;
    private String finishTime;
    private String settleTime;
    private String estimatedBillingAmount;
    private String estimatedCommission;
    private String commissionRate;
    private String shareRate;
    private String actualBillingAmount;
    private String actualCommission;
    private String quantity;
    private String productQuantity;
    private String afterSaleQuantity;
    private String returnQuantity;
    private String promotionInfo;
    private String promotionPosition;
    private String orderType;
}
