package org.rx.crawler.service.impl;

import com.google.common.net.HttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.rx.bean.FlagsEnum;
import org.rx.core.Arrays;
import org.rx.core.Linq;
import org.rx.crawler.Browser;
import org.rx.crawler.RegionFlags;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.service.CookieContainer;
import org.rx.net.http.HttpClient;
import org.rx.spring.SpringContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.rx.core.Extends.*;

@Slf4j
public class MemoryCookieContainer implements CookieContainer {
    private static final long waitMillis = 1000;
    final CookieHandler cookieHandler;
    final CookieStore cookieStore;

    public MemoryCookieContainer() {
        cookieHandler = new CookieManager();
        CookieManager mgr = (CookieManager) cookieHandler;
        mgr.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        cookieStore = mgr.getCookieStore();
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
        List<javax.servlet.http.Cookie> reqCookies = Arrays.toList(ifNull(request.getCookies(), new javax.servlet.http.Cookie[0]));
        switch (action) {
            case "loadTo":
                rawCookie = get(regionUrl);
                if (rawCookie != null) {
                    FlagsEnum<RegionFlags> flags = CookieContainer.getRegionFlags(regionUrl);
                    log.info("load cookie from url={}[{}]\n{}", regionUrl, flags.name(), rawCookie);
                    for (HttpCookie cookie : HttpCookie.parse("set-cookie2:" + rawCookie)) {
                        quietly(() -> {
                            javax.servlet.http.Cookie reqCookie = Linq.from(reqCookies).firstOrDefault(p -> eq(p.getName(), cookie.getName()));
                            if (reqCookie == null) {
                                reqCookie = new javax.servlet.http.Cookie(cookie.getName(), cookie.getValue());
                            } else {
                                reqCookies.remove(reqCookie);
                            }
                            copy(cookie, reqCookie);
                            //request cookie 只有name和value
                            reqCookie.setPath("/");
                            if (flags.has(RegionFlags.DOMAIN_TOP)) {
                                reqCookie.setDomain("." + reqHttpUrl.topPrivateDomain());
                                log.debug("set cookie {} with domain={}", reqCookie.getName(), reqCookie.getDomain());
                            }
                            if (flags.has(RegionFlags.HTTP_ONLY)) {
                                reqCookie.setHttpOnly(true);
                            }
                            response.addCookie(reqCookie);
                        });
                    }
                }
                for (javax.servlet.http.Cookie c : reqCookies) {
                    delCookie(response, c, reqHttpUrl.topPrivateDomain());
                }
                break;
            case "syncFrom":
                rawCookie = request.getHeader(HttpHeaders.COOKIE);
                log.debug("save cookie url={}\n{}\n", reqHttpUrl, rawCookie);
                save(reqHttpUrl.toString(), rawCookie);
                break;
            case "clearCookie":
                for (javax.servlet.http.Cookie servletCookie : reqCookies) {
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

    private void delCookie(HttpServletResponse response, javax.servlet.http.Cookie c, String domain) {
        c.setValue("");
        c.setPath("/");
        c.setMaxAge(0);
        response.addCookie(c);

        javax.servlet.http.Cookie dc = new javax.servlet.http.Cookie(c.getName(), "");
        dc.setDomain(domain);
        dc.setPath("/");
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

    @SneakyThrows
    @Override
    public void save(String url, String rawCookie) {
        if (rawCookie == null) {
            log.warn("empty cookie {}", url);
            return;
        }
        cookieHandler.put(URI.create(url), Collections.singletonMap(HttpHeaderNames.SET_COOKIE2.toString(), Collections.singletonList(rawCookie)));
    }

    @Override
    public void clear(String url) {
        URI uri = URI.create(url);
        for (HttpCookie cookie : cookieStore.get(uri)) {
            cookieStore.remove(uri, cookie);
        }
    }

    @SneakyThrows
    @Override
    public String get(String url) {
        List<String> cookies = cookieHandler.get(URI.create(url), Collections.emptyMap()).get("Cookie");
        return String.join("; ", cookies);
    }

    void copy(HttpCookie a, javax.servlet.http.Cookie b) {
        b.setDomain(a.getDomain());
        b.setPath(a.getPath());
        b.setHttpOnly(a.isHttpOnly());
        b.setSecure(a.getSecure());
        b.setMaxAge((int) a.getMaxAge());
        b.setComment(a.getComment());
        b.setVersion(a.getVersion());
        b.setValue(a.getValue());
    }
}
