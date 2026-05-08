package org.rx.crawler.task.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.crawler.service.BrowserType;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.impl.WebBrowser;
import org.rx.crawler.service.impl.WebBrowserConfig;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.rx.core.Extends.tryClose;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserProfileManager {
    private static final String SAFE_PROFILE_NAME = "[^a-zA-Z0-9_\\-]";

    private final AppConfig appConfig;
    private final Map<String, ReentrantLock> profileLocks = new ConcurrentHashMap<String, ReentrantLock>();
    private final Map<String, ProfileSession> sessions = new ConcurrentHashMap<String, ProfileSession>();

    public String defaultProfileName() {
        String profileName = appConfig.getCustom().getChrome().getDefaultProfileName();
        return Strings.isEmpty(profileName) ? "common" : normalizeProfileName(profileName);
    }

    public String normalizeProfileName(String profileName) {
        String value = Strings.isEmpty(profileName) ? defaultProfileName() : profileName.trim();
        value = value.replaceAll(SAFE_PROFILE_NAME, "-");
        return Strings.isEmpty(value) ? "common" : value;
    }

    public String resolveProfileDataPath(String profileName) {
        String basePath = appConfig.getCustom().getChrome().getProfileBasePath();
        if (Strings.isEmpty(basePath)) {
            basePath = new ChromeProfileConfig().getProfileBasePath();
        }
        String path = Paths.get(basePath, normalizeProfileName(profileName)).toString();
        try {
            java.nio.file.Files.createDirectories(Paths.get(path));
        } catch (Exception e) {
            throw new IllegalStateException("Create chrome profile path fail: " + path, e);
        }
        return path;
    }

    public ProfileLease acquire(String profileName, WebBrowserConfig config) {
        String normalized = normalizeProfileName(profileName);
        ReentrantLock lock = profileLocks.computeIfAbsent(normalized, k -> new ReentrantLock());
        lock.lock();
        try {
            ProfileSession session = sessions.get(normalized);
            if (session != null && (session.isExpired() || session.getBrowser().isClosed())) {
                sessions.remove(normalized);
                tryClose(session.getBrowser());
                session = null;
            }

            WebBrowser browser;
            boolean fromSession = session != null;
            if (fromSession) {
                browser = session.getBrowser();
                log.info("Reuse chrome profile session {}", normalized);
            } else {
                config.setProfileDataPath(resolveProfileDataPath(normalized));
                browser = new WebBrowser(config, BrowserType.CHROME);
                log.info("Open chrome profile {} at {}", normalized, config.getProfileDataPath());
            }
            return new ProfileLease(normalized, lock, browser, fromSession);
        } catch (RuntimeException e) {
            lock.unlock();
            throw e;
        }
    }

    public boolean closeSession(String profileName) {
        String normalized = normalizeProfileName(profileName);
        ReentrantLock lock = profileLocks.computeIfAbsent(normalized, k -> new ReentrantLock());
        lock.lock();
        try {
            ProfileSession session = sessions.remove(normalized);
            if (session == null) {
                return false;
            }
            tryClose(session.getBrowser());
            return true;
        } finally {
            lock.unlock();
        }
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    @PreDestroy
    public void destroy() {
        for (ProfileSession session : sessions.values()) {
            tryClose(session.getBrowser());
        }
        sessions.clear();
    }

    @Getter
    private static class ProfileSession {
        private final WebBrowser browser;
        private final long expireAtMillis;

        ProfileSession(WebBrowser browser, long expireAtMillis) {
            this.browser = browser;
            this.expireAtMillis = expireAtMillis;
        }

        boolean isExpired() {
            return expireAtMillis > 0 && System.currentTimeMillis() > expireAtMillis;
        }
    }

    public class ProfileLease implements AutoCloseable {
        @Getter
        private final String profileName;
        private final ReentrantLock lock;
        @Getter
        private final WebBrowser browser;
        @Getter
        private final boolean fromSession;
        private boolean keepOpen;
        private long keepOpenSeconds;

        ProfileLease(String profileName, ReentrantLock lock, WebBrowser browser, boolean fromSession) {
            this.profileName = profileName;
            this.lock = lock;
            this.browser = browser;
            this.fromSession = fromSession;
        }

        public void keepOpen(long seconds) {
            keepOpen = true;
            keepOpenSeconds = seconds <= 0 ? TimeUnit.MINUTES.toSeconds(5) : seconds;
        }

        @Override
        public void close() {
            try {
                if (keepOpen) {
                    long expireAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(keepOpenSeconds);
                    sessions.put(profileName, new ProfileSession(browser, expireAt));
                    log.info("Keep chrome profile {} open {} seconds", profileName, keepOpenSeconds);
                    return;
                }

                ProfileSession session = sessions.get(profileName);
                if (session != null && session.getBrowser() == browser) {
                    sessions.remove(profileName);
                }
                tryClose(browser);
            } finally {
                lock.unlock();
            }
        }
    }
}
