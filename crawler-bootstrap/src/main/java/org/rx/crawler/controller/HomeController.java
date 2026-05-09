package org.rx.crawler.controller;

import com.google.common.net.HttpHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.core.Strings;
import org.rx.crawler.config.AppConfig;
import org.rx.net.http.HttpClientCookieJar;
import org.rx.net.http.HttpClient;
import org.rx.redis.RedisCache;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;

import static org.rx.core.Extends.ifNull;
import static org.rx.core.Sys.cacheKey;

@Slf4j
@RequiredArgsConstructor
@Controller
public class HomeController {
    private final RedisCache<String, String> cache;
    private final HttpClientCookieJar cookieJar;

    @RequestMapping("/pddName")
    @ResponseBody
    public boolean pddName(String u, String n) {
        Map<String, String> data = HttpClient.decodeQueryString(u);
        String goodsId = data.get("goods_id");
        if (Strings.isEmpty(goodsId)) {
            log.warn("pddName fail, u={} n={}", u, n);
            return false;
        }
        String ck = cacheKey(AppConfig.CACHE_PDD_GOODS_MAP, goodsId);
        log.info("pddName {} ok, u={} n={}", ck, u, n);
        cache.put(ck, n);
        return true;
    }

    @RequestMapping("/cookies.html")
    public String cookies(String regionUrl, String action, String writeBack, Model model, HttpServletRequest request) {
        model.addAttribute("title", "BrowserCallback");
        model.addAttribute("name", "王湵范");

        String reqRawCookie = ifNull(request.getHeader(HttpHeaders.COOKIE), Strings.EMPTY);
        String url = Strings.isEmpty(regionUrl) ? request.getRequestURL().toString() : regionUrl;
        String jarRawCookie = Strings.EMPTY;
        if (!Strings.isEmpty(url)) {
            try {
                jarRawCookie = ifNull(cookieJar.loadForRequest(URI.create(url)), Strings.EMPTY);
            } catch (Exception e) {
                log.warn("load jar cookie fail, url={}, error={}", url, e.getMessage());
            }
        }
        if (Strings.isEmpty(reqRawCookie)) {
            log.info("{} doesn't have any cookies..", url);
        }
        String k = cacheKey(url);
        model.addAttribute("regionUrl", url);
        model.addAttribute("action", action);
        model.addAttribute("requestCookie", reqRawCookie);
        model.addAttribute("rawCookie", jarRawCookie);
        Cache.getInstance().put(k, reqRawCookie);
        return "cookies";
    }

    @RequestMapping("/cookies/raw")
    @ResponseBody
    public String cookiesRaw(String url) {
        if (Strings.isEmpty(url)) {
            return Strings.EMPTY;
        }
        try {
            return ifNull(cookieJar.loadForRequest(URI.create(url)), Strings.EMPTY);
        } catch (Exception e) {
            log.warn("load raw cookie fail, url={}, error={}", url, e.getMessage());
            return Strings.EMPTY;
        }
    }

    @RequestMapping("/health")
    @ResponseBody
    public String health() {
        return "ok";
    }
}
