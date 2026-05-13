package org.rx.crawler.task.tb;

import lombok.Data;

import java.io.Serializable;

@Data
public class TbPromotionOrderItem implements Serializable {
    private static final long serialVersionUID = 6949848171794104383L;

    /**
     * 商品名称。
     */
    private String productName;
    /**
     * 商品超链接；页面 DOM 未提供时为空。
     */
    private String productLink;
    /**
     * 商品价格。
     */
    private String productPrice;
    /**
     * 店铺名。
     */
    private String storeName;
    /**
     * 订单号。
     */
    private String orderNo;
    /**
     * 主单号。
     */
    private String mainOrderNo;
    /**
     * 订单状态。
     */
    private String orderStatus;
    /**
     * 下单时间。
     */
    private String orderTime;
    /**
     * 预估计佣金额。
     */
    private String estimatedBillingAmount;
    /**
     * 预估佣金。
     */
    private String estimatedCommission;
    /**
     * 佣金比例。
     */
    private String commissionRate;
    /**
     * 实际计佣金额。
     */
    private String actualBillingAmount;
    /**
     * 实际佣金；页面展示为 -- 时为空。
     */
    private String actualCommission;
}
