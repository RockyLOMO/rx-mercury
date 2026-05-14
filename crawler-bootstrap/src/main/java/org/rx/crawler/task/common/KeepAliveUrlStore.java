package org.rx.crawler.task.common;

import org.rx.crawler.service.Browser;

import java.util.List;
import java.util.Map;

public interface KeepAliveUrlStore {
    KeepAliveUrlStore NOOP = new KeepAliveUrlStore() {
        @Override
        public List<String> getCandidateUrls(String platform, List<String> defaults) {
            return defaults;
        }

        @Override
        public void collect(String platform, Browser browser, Map<String, Object> diagnostics) {
        }
    };

    List<String> getCandidateUrls(String platform, List<String> defaults);

    void collect(String platform, Browser browser, Map<String, Object> diagnostics);
}
