package org.rx.crawler.config;

import com.alibaba.fastjson2.TypeReference;
import lombok.Data;
import org.openqa.selenium.Rectangle;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.crawler.dto.CrawlPageConfig;
import org.rx.util.IdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static org.rx.core.Sys.fromJson;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
@RefreshScope
@Validated
@Order(1)
public class AppConfig {
    public static final String CACHE_PDD_GOODS_MAP = "RubbishPdd";

    @Data
    public static class BrowserPoolConfig {
        private int listenPort;
        private long maintenancePeriod;
        private int maxActiveMinutes;
        private long dumpPeriod;
        private float asyncThreshold;
        private int takeTimeoutSeconds = 6;
        private String remotingPortRange = "1220-1320";
        private IdGenerator portGenerator;

        private int poolSize = 2;
        private boolean windowAutoBlank = true;
        private long waitMillis = 500;
        private int pageLoadTimeoutSeconds = 30;
        private int findElementTimeoutSeconds = 6;
        private String diskDataPath;
        private String downloadPath = "/app-crawler/temp/";
        private Rectangle windowRectangle;
        private String cookieContainerType;
        private String configureScriptExecutorType = "org.rx.crawler.service.impl.ApiConfigureScriptExecutor";

        public IdGenerator getPortGenerator() {
            if (portGenerator == null) {
                Linq<Integer> q = Linq.from(Strings.split(remotingPortRange, "-", 2)).select(p -> Integer.valueOf(p));
                portGenerator = new IdGenerator(q.first(), q.last());
            }
            return portGenerator;
        }
    }

    private String chromeDriver = "/app-crawler/driver/chromedriver.exe";
    private String fireFoxDriver = "/app-crawler/driver/geckodriver.exe";
    private String ieDriver = "/app-crawler/driver/IEDriverServer.exe";
    private BrowserPoolConfig browser = new BrowserPoolConfig();

    private int fiddlerListenPort;
    private String cleanTaskTime = "03:00:00";
    private String baseDir = "";
    @Value("${server.httpPort}")
    private int httpPort;
    @Value("${server.port}")
    private int httpsPort;

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
