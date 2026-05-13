package org.rx.crawler.task.tb;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;

@Data
public class TbPromotionUrlRequest implements Serializable {
    private static final long serialVersionUID = 1641528783140379143L;

    @NotBlank
    private String productInfo;
    @NotBlank
    @Pattern(regexp = "\\d+", message = "adSiteName must be digits")
    private String adSiteName;

    private String mediaName;
    private String profileName;
    private Boolean forcePreflight;
    private Boolean keepBrowserOpenOnLoginRequired;
    private String outputPath;
    private Boolean debugEnabled;
    private String debugOutputDir;
}
