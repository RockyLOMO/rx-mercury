package org.rx.crawler.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrawlPageConfig implements Serializable {
    private String url;
    private String locatorSelector;
    private int timeoutSeconds;
    private String scriptName;
}
