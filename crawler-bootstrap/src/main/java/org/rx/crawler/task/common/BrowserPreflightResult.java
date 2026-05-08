package org.rx.crawler.task.common;

import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Data
public class BrowserPreflightResult implements Serializable {
    private static final long serialVersionUID = 4715925626818627697L;

    private boolean passed;
    private boolean cached;
    private String message;
    private Map<String, Object> diagnostics = new HashMap<String, Object>();

    public static BrowserPreflightResult pass(boolean cached) {
        BrowserPreflightResult result = new BrowserPreflightResult();
        result.setPassed(true);
        result.setCached(cached);
        result.setMessage("");
        return result;
    }

    public static BrowserPreflightResult fail(String message) {
        BrowserPreflightResult result = new BrowserPreflightResult();
        result.setPassed(false);
        result.setMessage(message);
        return result;
    }
}
