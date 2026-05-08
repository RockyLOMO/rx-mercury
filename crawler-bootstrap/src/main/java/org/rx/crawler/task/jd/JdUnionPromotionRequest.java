package org.rx.crawler.task.jd;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
public class JdUnionPromotionRequest implements Serializable {
    private static final long serialVersionUID = -5098514354965033261L;

    @NotBlank
    private String skuId;
    @NotBlank
    private String adSiteName;

    private String mediaType;
    private String mediaName;
    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private String outputPath;
}
