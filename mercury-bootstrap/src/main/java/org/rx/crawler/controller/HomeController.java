package org.rx.crawler.controller;

import com.google.common.net.HttpHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.common.AppConfig;
import org.rx.core.Cache;
import org.rx.core.Strings;
import org.rx.net.http.HttpClient;
import org.rx.redis.RedisCache;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;

import static org.rx.core.App.*;

@Slf4j
@RequiredArgsConstructor
@Controller
public class HomeController {
    private final AppConfig config;
    private final RedisCache<String, String> cache;

    @RequestMapping("/pddName")
    @ResponseBody
    public boolean pddName(String u, String n) {
        Map<String, String> data = HttpClient.decodeQueryString(u);
        String goodsId = data.get("goods_id");
        if (Strings.isEmpty(goodsId)) {
            log.warn("pddName fail, u={} n={}", u, n);
            return false;
        }
        String ck = cacheKey(String.format(AppConfig.PDD_GOODS_MAP_FORMAT, goodsId));
        log.info("pddName {} ok, u={} n={}", ck, u, n);
        cache.put(ck, n);
        return true;
    }

    @RequestMapping("/cookies.html")
    public String cookies(String regionUrl, String action, String writeBack, Model model, HttpServletRequest request, HttpServletResponse response) {
        boolean isWriteBack = !Strings.isEmpty(writeBack);
        model.addAttribute("title", String.format("BrowserCallback%s", isWriteBack ? "-WriteBack" : ""));
        model.addAttribute("name", "王湵范");

//        String reqUrl = request.getRequestURL().toString();
        String reqRawCookie = isNull(request.getHeader(HttpHeaders.COOKIE), Strings.EMPTY);
        if (Strings.isEmpty(reqRawCookie)) {
            log.info("{} doesn't have any cookies..", regionUrl);
        }
        String k = cacheKey(regionUrl);
        model.addAttribute("regionUrl", regionUrl);
        model.addAttribute("action", action);
        Cache<String, String> cache = Cache.getInstance(Cache.MAIN_CACHE);
        if (isWriteBack) {
            model.addAttribute("requestCookie", cache.get(k));
            model.addAttribute("rawCookie", isNull(config.getCookieContainer().get(regionUrl), Strings.EMPTY));
        } else {
            model.addAttribute("requestCookie", reqRawCookie);
            cache.put(k, reqRawCookie);
            return config.getCookieContainer().handleWriteRequest(request, response);
        }
        return "cookies";
    }

    @RequestMapping("/health")
    @ResponseBody
    public String health() {
        return "ok";
    }
}
