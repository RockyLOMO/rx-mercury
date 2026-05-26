package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class BrowserProfileManagerTest {
    @TempDir
    Path tempDir;

    @Test
    public void taskCompletionShouldKeepAndReuseChromeSessionUntilExplicitClose() throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        BrowserProfileManager manager = new BrowserProfileManager(config);

        try (MockedConstruction<WebBrowser> constructed = mockConstruction(WebBrowser.class)) {
            WebBrowser firstBrowser;
            try (BrowserProfileManager.ProfileLease lease = manager.acquire("common", new WebBrowserConfig())) {
                firstBrowser = lease.getBrowser();
                assertFalse(lease.isFromSession());
            }

            assertEquals(1, manager.activeSessionCount());
            try (BrowserProfileManager.ProfileLease lease = manager.acquire("common", new WebBrowserConfig())) {
                assertTrue(lease.isFromSession());
                assertSame(firstBrowser, lease.getBrowser());
            }

            assertEquals(1, constructed.constructed().size());
            verify(firstBrowser, times(2)).saveCookies(false);
            verify(firstBrowser, never()).close();

            assertTrue(manager.closeSession("common"));
            assertEquals(0, manager.activeSessionCount());
            verify(firstBrowser).close();
        }
    }

    @Test
    public void configuredCloseAfterTaskShouldCloseChromeWithoutCreatingReusableSession() throws Exception {
        AppConfig config = new AppConfig();
        config.getCustom().getChrome().setProfileBasePath(tempDir.toString());
        config.getCustom().getChrome().setCloseBrowserAfterTask(true);
        BrowserProfileManager manager = new BrowserProfileManager(config);

        try (MockedConstruction<WebBrowser> constructed = mockConstruction(WebBrowser.class)) {
            WebBrowser browser;
            try (BrowserProfileManager.ProfileLease lease = manager.acquire("common", new WebBrowserConfig())) {
                browser = lease.getBrowser();
            }

            assertEquals(1, constructed.constructed().size());
            assertEquals(0, manager.activeSessionCount());
            verify(browser).saveCookies(false);
            verify(browser).close();
        }
    }
}
