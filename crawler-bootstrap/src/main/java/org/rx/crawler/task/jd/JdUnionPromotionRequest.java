package org.rx.crawler.task.jd;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import java.io.Serializable;

@Data
public class JdUnionPromotionRequest implements Serializable {
    private static final long serialVersionUID = -5098514354965033261L;

    @NotBlank
    @Pattern(regexp = "\\d+", message = "skuId must be digits")
    private String skuId;
    @NotBlank
    @Pattern(regexp = "\\d+", message = "adSiteName must be digits")
    private String adSiteName;

    private String mediaType;
    private String mediaName;
    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private String outputPath;
    private Boolean debugEnabled;
    private String debugOutputDir;
}
