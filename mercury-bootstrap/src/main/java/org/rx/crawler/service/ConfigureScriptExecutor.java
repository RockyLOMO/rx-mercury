package org.rx.crawler.service;

import org.rx.crawler.Browser;

public interface ConfigureScriptExecutor {
    Browser getOwner();

    String getConfigureScript(String scriptName);

    void setConfigureScript(String scriptName, String scriptContent);

    <T> T execute(String scriptName, Object... args);
}
