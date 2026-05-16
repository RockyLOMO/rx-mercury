package org.rx.crawler.task.common;

import lombok.RequiredArgsConstructor;
import org.rx.core.Extends;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CrawlEntryService {
    private final BrowserPreflightService preflightService;
    private final LoginNotificationService loginNotificationService;

    public CrawlEntryService(BrowserPreflightService preflightService) {
        this(preflightService, LoginNotificationService.NOOP);
    }

    public CrawlEntryResult enter(Browser browser, BrowserProfileManager.ProfileLease lease, CrawlEntryOptions options)
            throws TimeoutException {
        CrawlEntryResult result = new CrawlEntryResult();
        if (!runPreflight(browser, options, result)) {
            return result;
        }

        browser.navigateUrl(options.getInitialUrl(), Browser.BODY_SELECTOR, options.getInitialPageTimeoutSeconds());
        Extends.sleep(options.nextStepDelayMillis());
        result.setCurrentUrl(browser.getCurrentUrl());
        if (!isLoginRequired(result.getCurrentUrl(), options)) {
            result.setPassed(true);
            return result;
        }

        result.setLoginRequired(true);
        notifyLoginRequired(options, result.getCurrentUrl(),
                "Login required. Finish login in the opened Chrome profile, then retry.");
        result.getDiagnostics().put("loginNotificationAttempted", true);
        if (options.isKeepBrowserOpenOnLoginRequired() && waitLoginCompleted(browser, options, result)) {
            result.setLoginRequired(false);
            result.setCurrentUrl(browser.getCurrentUrl());
            result.getDiagnostics().put("loginWaitCompleted", true);
            result.setPassed(true);
            return result;
        }

        result.setStatus(CustomCrawlStatus.LOGIN_REQUIRED);
        result.setMessage("Login required. Finish login in the opened Chrome profile, then retry.");
        if (options.isKeepBrowserOpenOnLoginRequired()) {
            lease.keepOpen(options.getKeepBrowserOpenSecondsOnLoginRequired());
        }
        return result;
    }

    public void notifyLoginRequired(LoginNotificationContext context) {
        loginNotificationService.notifyLoginRequired(context);
    }

    public void notifyLoginRequired(CrawlEntryOptions options, String currentUrl, String message) {
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType(options.getTaskType());
        context.setProfileName(options.getProfileName());
        context.setInitialUrl(options.getInitialUrl());
        context.setCurrentUrl(currentUrl);
        context.setMessage(message);
        context.setLoginWaitSeconds(options.getLoginWaitSeconds());
        context.setKeepBrowserOpenSeconds(options.getKeepBrowserOpenSecondsOnLoginRequired());
        notifyLoginRequired(context);
    }

    private boolean runPreflight(Browser browser, CrawlEntryOptions options, CrawlEntryResult result) {
        BrowserPreflightResult preflight = preflightService.check(browser, options, options.isForcePreflight());
        result.setFingerprintPassed(preflight.isPassed());
        result.getDiagnostics().put("preflight", preflight);
        if (preflight.isPassed()) {
            return true;
        }

        result.setStatus(CustomCrawlStatus.BROWSER_FINGERPRINT_CHECK_FAILED);
        result.setMessage(preflight.getMessage());
        return false;
    }

    private boolean waitLoginCompleted(Browser browser, CrawlEntryOptions options, CrawlEntryResult result) {
        int waitSeconds = Math.max(0, options.getLoginWaitSeconds());
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        result.getDiagnostics().put("loginWaitSeconds", waitSeconds);
        while (System.currentTimeMillis() < deadline) {
            Extends.sleep(Math.max(1000, options.nextStepDelayMillis()));
            String currentUrl = browser.getCurrentUrl();
            result.setCurrentUrl(currentUrl);
            if (isLoginRequired(currentUrl, options)) {
                continue;
            }
            if (isLoggedInUrl(currentUrl, options) || isInitialUrl(currentUrl, options)) {
                return true;
            }
            String body = bodySnippet(browser);
            if (options.getLoggedInBodyMatcher() != null && options.getLoggedInBodyMatcher().test(body)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLoginRequired(String currentUrl, CrawlEntryOptions options) {
        if (Strings.isEmpty(currentUrl)) {
            return false;
        }
        if (options.getLoginRequiredUrlMatcher() != null) {
            return options.getLoginRequiredUrlMatcher().test(currentUrl);
        }
        String lower = currentUrl.toLowerCase(Locale.ROOT);
        return (!Strings.isEmpty(options.getLoginUrlPrefix()) && currentUrl.startsWith(options.getLoginUrlPrefix()))
                || lower.contains("passport.")
                || lower.contains("/login");
    }

    private boolean isLoggedInUrl(String currentUrl, CrawlEntryOptions options) {
        return !Strings.isEmpty(currentUrl)
                && options.getLoggedInUrlMatcher() != null
                && options.getLoggedInUrlMatcher().test(currentUrl);
    }

    private boolean isInitialUrl(String currentUrl, CrawlEntryOptions options) {
        return !Strings.isEmpty(currentUrl)
                && !Strings.isEmpty(options.getInitialUrl())
                && currentUrl.startsWith(options.getInitialUrl());
    }

    private String bodySnippet(Browser browser) {
        String body = "";
        for (int i = 0; i < 3; i++) {
            try {
                body = browser.executeScript("return document.body ? document.body.innerText : '';");
                break;
            } catch (Exception e) {
                String message = e.getMessage();
                if (message != null && (message.contains("Execution context was destroyed") || message.contains("because of a navigation"))) {
                    Extends.sleep(500L * (i + 1));
                    continue;
                }
                return "";
            }
        }
        if (Strings.isEmpty(body) || body.length() <= 2000) {
            return body;
        }
        return body.substring(0, 2000);
    }
}
