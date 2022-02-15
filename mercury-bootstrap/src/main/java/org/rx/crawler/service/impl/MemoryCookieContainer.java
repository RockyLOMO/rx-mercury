package org.rx.crawler.service.impl;

import com.google.common.net.HttpHeaders;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.rx.bean.FlagsEnum;
import org.rx.crawler.Browser;
import org.rx.crawler.RegionFlags;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.CookieContainer;
import org.rx.core.Arrays;
import org.rx.core.NQuery;
import org.rx.core.Strings;
import org.rx.net.http.HttpClient;
import org.rx.redis.RedisCache;
import org.rx.spring.SpringContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.rx.core.App.*;
import static org.rx.core.Extends.*;

@Slf4j
public class MemoryCookieContainer implements CookieContainer {
    private static final long waitMillis = 1000;

    private RedisCache<String, String> getStore() {
        return SpringContext.getBean(RedisCache.class);
    }

    @Override
    public String handleWriteRequest(HttpServletRequest request, HttpServletResponse response) {
        String regionUrl = request.getParameter("regionUrl"),
                action = request.getParameter("action");
//                writeBack = request.getParameter("writeBack"),
//                reqUrl = request.getRequestURL().toString();
//        response.addHeader("P3P", "CP='CURa ADMa DEVa PSAo PSDo OUR BUS UNI PUR INT DEM STA PRE COM NAV OTC NOI DSP COR'");
        String rawCookie;
        HttpUrl reqHttpUrl = HttpUrl.get(regionUrl);
        List<javax.servlet.http.Cookie> servletCookies = Arrays.toList(ifNull(request.getCookies(), new javax.servlet.http.Cookie[0]));
        switch (action) {
            case "loadTo":
                rawCookie = get(regionUrl);
                if (rawCookie != null) {
                    FlagsEnum<RegionFlags> flags = CookieContainer.getRegionFlags(regionUrl);
                    log.debug("load cookie url={} flags={}\n{}\n", regionUrl, flags.name(), rawCookie);
                    for (Cookie cookie : HttpClient.decodeCookie(reqHttpUrl, rawCookie)) {
                        quietly(() -> {
                            javax.servlet.http.Cookie servletCookie = NQuery.of(servletCookies).firstOrDefault(p -> eq(p.getName(), cookie.name()));
                            boolean isChange;
                            if (servletCookie == null) {
                                servletCookie = new javax.servlet.http.Cookie(cookie.name(), cookie.value());
                                servletCookie.setPath("/");
                                isChange = true;
                            } else {
                                if (isChange = !Strings.equals(servletCookie.getValue(), cookie.value())) {
                                    servletCookie.setValue(cookie.value());
                                }
                                servletCookies.remove(servletCookie);
                            }
                            if (isChange) {
                                //request cookie 只有name和value
                                if (flags.has(RegionFlags.HTTP_ONLY)) {
                                    servletCookie.setHttpOnly(true);
                                }
                                if (flags.has(RegionFlags.DOMAIN_TOP)) {
                                    servletCookie.setDomain(cookie.domain());
                                    log.debug("set cookie {} with domain={}", servletCookie.getName(), servletCookie.getDomain());
                                }
                                response.addCookie(servletCookie);
                            }
                        });
                    }
                }
                for (javax.servlet.http.Cookie servletCookie : servletCookies) {
                    delCookie(response, servletCookie, reqHttpUrl.topPrivateDomain());
                }
                break;
            case "syncFrom":
                rawCookie = request.getHeader(HttpHeaders.COOKIE);
                log.debug("save cookie url={}\n{}\n", reqHttpUrl, rawCookie);
                save(reqHttpUrl.toString(), rawCookie);
                break;
            case "clearCookie":
                for (javax.servlet.http.Cookie servletCookie : servletCookies) {
                    delCookie(response, servletCookie, reqHttpUrl.topPrivateDomain());
                }
                break;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("regionUrl", regionUrl);
        params.put("action", action);
        params.put("writeBack", 1);
        return String.format("redirect:%s", HttpClient.buildUrl(request.getRequestURL().toString(), params));
    }

    private void delCookie(HttpServletResponse response, javax.servlet.http.Cookie servletCookie, String domain) {
        servletCookie.setValue("");
        servletCookie.setPath("/");
        servletCookie.setMaxAge(0);
        response.addCookie(servletCookie);

        javax.servlet.http.Cookie dc = new javax.servlet.http.Cookie(servletCookie.getName(), "");
        dc.setPath("/");
        dc.setDomain(domain);
        dc.setMaxAge(0);
        response.addCookie(dc);
    }

    @SneakyThrows
    @Override
    public void loadTo(@NonNull Browser browser, @NonNull String regionUrl) {
        Map<String, Object> query = new HashMap<>();
        query.put("regionUrl", regionUrl);
        query.put("action", "loadTo");
        browser.nativeGet(getWriteUrl(regionUrl, query));
        browser.waitElementLocated(Selector);
        sleep(waitMillis);
    }

    @SneakyThrows
    @Override
    public String syncFrom(@NonNull Browser browser, @NonNull String regionUrl) {
        Map<String, Object> query = new HashMap<>();
        query.put("regionUrl", regionUrl);
        query.put("action", "syncFrom");
        browser.nativeGet(getWriteUrl(regionUrl, query));
        browser.waitElementLocated(Selector);
//        browser.navigateUrl(getWriteUrl(url, query), Selector);
        sleep(waitMillis);
        return browser.elementAttr(Selector, "value");
    }

    @SneakyThrows
    @Override
    public void clearCookie(@NonNull Browser browser, @NonNull String regionUrl) {
        Map<String, Object> query = new HashMap<>();
        query.put("regionUrl", regionUrl);
        query.put("action", "clearCookie");
        browser.nativeGet(getWriteUrl(regionUrl, query));
        browser.waitElementLocated(Selector);
        sleep(waitMillis);
    }

    @Override
    public String getWriteUrl(String url, Map<String, Object> query) {
        int port = SpringContext.getBean(AppConfig.class).getHttpsPort();
        String u = HttpClient.buildUrl(String.format("https://x.%s:%s/cookies.html", CookieContainer.getCookieDomain(url), port), query);
        log.info("getWriteUrl {}, port={}", u, port);
        return u;
    }

    @Override
    public void save(String url, String rawCookie) {
        if (rawCookie == null) {
            log.warn("empty cookie {}", url);
            return;
        }
        getStore().put(getKey(url), rawCookie);
    }

    private String getKey(String url) {
        return cacheKey(CookieContainer.getCookieDomain(url), CookieContainer.getRegionName(url));
    }

    @Override
    public void clear(String url) {
        getStore().remove(getKey(url));
    }

    @Override
    public String get(String url) {
        return getStore().get(getKey(url));
    }
}
