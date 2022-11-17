package org.rx.crawler.config;

import com.alibaba.fastjson2.TypeReference;
import com.ctrip.framework.apollo.spring.annotation.ApolloJsonValue;
import lombok.Data;
import org.openqa.selenium.Rectangle;
import org.rx.crawler.dto.CrawlPageConfig;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.crawler.service.BrowserPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.io.File;
import java.util.List;

import static org.rx.core.Sys.fromJson;

@Data
@Component
@ConfigurationProperties(prefix = "app")
@Validated
@Order(1)
public class AppConfig {
    @Data
    public static class ProxyConfig {
        @ApolloJsonValue("${app.proxy.socks5:[]}")
        private List<String> socks5;
        @ApolloJsonValue("${app.proxy.website:[]}")
        private List<CrawlPageConfig> website;
        private String[] produceProxyTimes;
        private long maxLagMills = 200;
        private int queueSyncCount = 1000;
        private int queueMinCountToRaiseProduce = 8;
    }

    public static final String CACHE_PDD_GOODS_MAP = "RubbishPdd";
    private ProxyConfig proxy;

    private String chromeDriver;
    private String ieDriver;
    private BrowserPoolConfig browser;

    private int fiddlerListenPort;
    @Value("${server.httpPort}")
    private int httpPort;
    @Value("${server.port}")
    private int httpsPort;

    public String getFiddlerPath() {
        return new File("./fiddler").getAbsolutePath();
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class RectangleConverter implements Converter<String, Rectangle> {
        @Override
        public Rectangle convert(String windowRectangle) {
            if (Strings.isEmpty(windowRectangle)) {
                return null;
            }
            List<Integer> list = Linq.from(windowRectangle.split(",")).select(p -> Integer.valueOf(p)).toList();
            //width height 反了
            return new Rectangle(list.get(0), list.get(1), list.get(3), list.get(2));
        }
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class CrawlPageConfigConverter implements Converter<String, List<CrawlPageConfig>> {
        @Override
        public List<CrawlPageConfig> convert(String s) {
            return fromJson(s, new TypeReference<List<CrawlPageConfig>>() {
            }.getType());
        }
    }
}
