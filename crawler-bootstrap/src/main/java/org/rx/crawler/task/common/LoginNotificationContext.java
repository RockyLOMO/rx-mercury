package org.rx.crawler.task.common;

import lombok.Data;

@Data
public class LoginNotificationContext {
    private String taskType;
    private String profileName;
    private String initialUrl;
    private String currentUrl;
    private String message;
    private int loginWaitSeconds;
    private int keepBrowserOpenSeconds;
}
