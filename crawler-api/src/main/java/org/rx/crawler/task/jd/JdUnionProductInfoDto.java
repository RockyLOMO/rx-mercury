package org.rx.crawler.task.jd;

import lombok.Data;

import java.io.Serializable;

@Data
public class JdUnionProductInfoDto implements Serializable {
    private static final long serialVersionUID = -8128369459494691492L;

    private String imageUrl;
    private String productName;
    private String productLink;
    private String commissionRate;
    private String finalPrice;
    private String storeName;
}
