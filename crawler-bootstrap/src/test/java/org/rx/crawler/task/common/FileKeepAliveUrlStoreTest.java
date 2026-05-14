package org.rx.crawler.task.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.crawler.config.AppConfig;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileKeepAliveUrlStoreTest {
    @TempDir
    Path tempDir;

    @Test
    public void shouldKeepOnlySameDomainUrls() {
        AppConfig config = new AppConfig();
        config.getCustom().getLoginKeepAlive().setUrlStorePath(tempDir.resolve("keepalive.json").toString());
        FileKeepAliveUrlStore store = new FileKeepAliveUrlStore(config, new ObjectMapper());

        List<String> urls = store.filterSameDomainUrls("https://union.jd.com/overview", Arrays.asList(
                "https://union.jd.com/order",
                "https://union.jd.com/proManager/index?pageNo=1#hash",
                "https://passport.jd.com/new/login.aspx",
                "https://item.jd.com/1001.html",
                "javascript:void(0)"));

        assertEquals(2, urls.size());
        assertTrue(urls.contains("https://union.jd.com/order"));
        assertTrue(urls.contains("https://union.jd.com/proManager/index?pageNo=1"));
    }

    @Test
    public void harvestedUrlsShouldBePreferredOverDefaults() {
        AppConfig config = new AppConfig();
        config.getCustom().getLoginKeepAlive().setUrlStorePath(tempDir.resolve("keepalive.json").toString());
        config.getCustom().getLoginKeepAlive().setMaxUrlsPerPlatform(10);
        FileKeepAliveUrlStore store = new FileKeepAliveUrlStore(config, new ObjectMapper());

        store.saveUrls("jd", Arrays.asList(
                "https://union.jd.com/order",
                "https://union.jd.com/proManager/index?pageNo=1"));
        List<String> urls = store.getCandidateUrls("jd", Arrays.asList(
                "https://union.jd.com/overview",
                "https://union.jd.com/order"));

        assertEquals("https://union.jd.com/order", urls.get(0));
        assertEquals("https://union.jd.com/proManager/index?pageNo=1", urls.get(1));
        assertTrue(urls.contains("https://union.jd.com/overview"));
        assertFalse(urls.isEmpty());
    }
}
