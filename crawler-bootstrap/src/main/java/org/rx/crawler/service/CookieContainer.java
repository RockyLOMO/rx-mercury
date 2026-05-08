package org.rx.crawler.service;

import lombok.NonNull;
import com.google.common.net.InternetDomainName;
import org.rx.bean.FlagsEnum;
import org.rx.core.Strings;
import org.rx.net.http.HttpClient;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Collections;
import java.util.Map;

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
        String host = URI.create(url).getHost();
        if (Strings.isEmpty(host)) {
            return Strings.EMPTY;
        }
        try {
            InternetDomainName domainName = InternetDomainName.from(host);
            return domainName.isUnderPublicSuffix() ? domainName.topPrivateDomain().toString() : host;
        } catch (Exception e) {
            return host;
        }
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
