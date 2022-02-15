package org.rx.crawler.service;

import lombok.NonNull;
import okhttp3.HttpUrl;
import org.rx.bean.FlagsEnum;
import org.rx.core.Strings;
import org.rx.crawler.Browser;
import org.rx.crawler.RegionFlags;
import org.rx.net.http.HttpClient;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Map;

import static org.rx.core.Extends.ifNull;

public interface CookieContainer {
    String Selector = "#cookie", Region = "_Region";

    static FlagsEnum<RegionFlags> getRegionFlags(@NonNull String regionUrl) {
        String regionName = getRegionName(regionUrl);
        if (!regionName.contains("_")) {
            return RegionFlags.NONE.flags();
        }

        return FlagsEnum.valueOf(RegionFlags.class, Integer.parseInt(Strings.split(regionName, "_", 2)[1]));
    }

    static String getRegionName(@NonNull String regionUrl) {
        return HttpClient.decodeQueryString(regionUrl).getOrDefault(Region, Strings.EMPTY);
    }

    static String buildRegionUrl(String url, String regionName) {
        return HttpClient.buildUrl(url, Collections.singletonMap(Region, regionName));
    }

    static String getCookieDomain(@NonNull String url) {
        HttpUrl httpUrl = HttpUrl.get(url);
        return ifNull(httpUrl.topPrivateDomain(), httpUrl.host());
    }

    String handleWriteRequest(HttpServletRequest request, HttpServletResponse response);

    void loadTo(Browser browser, String regionUrl);

    String syncFrom(Browser browser, String regionUrl);

    void clearCookie(Browser browser, String regionUrl);

    //https cookie 获取不到
    String getWriteUrl(String url, Map<String, Object> query);

    void save(String url, String rawCookie);

    void clear(String url);

    String get(String url);
}
