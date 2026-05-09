package org.rx.crawler.config;

import com.alibaba.fastjson2.TypeReference;
import lombok.Data;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.crawler.dto.BrowserWindowRect;
import org.rx.crawler.dto.CrawlPageConfig;
import org.rx.crawler.task.common.ChromeProfileConfig;
import org.rx.crawler.task.jd.JdUnionConfig;
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
        private long maintenancePeriod;
        private int maxActiveMinutes;
        private long dumpPeriod;
        private int takeTimeoutSeconds = 6;
        private String remotingPortRange = "1220-1320";
        private IdGenerator portGenerator;

        private int poolSize = 2;
        private boolean windowAutoBlank = true;
        private long waitMillis = 500;
        private int pageLoadTimeoutSeconds = 30;
        private int findElementTimeoutSeconds = 6;
        private boolean headless = true;
        private String profileDataPath;
        private String downloadPath = "/app-crawler/temp/";
        private BrowserWindowRect windowRectangle;
        private String cookieContainerType;
        private String configureScriptExecutorType = "org.rx.crawler.service.impl.ApiConfigureScriptExecutor";
        private boolean fingerprintEnabled = false;
        private String fingerprintScriptPath = "/bot/chrome-fingerprint.js";
        private String fingerprintStealthScriptPath = "/static/js/stealth.min.js";
        private String fingerprintCheckUrl = "https://bot.sannysoft.com/";
        private boolean fingerprintHeadless = false;
        private boolean fingerprintDiagnostics = false;
        private String playwrightChannel = "chrome";
        private String playwrightExecutablePath;
        private String locale = "zh-CN";
        private String timezoneId = "Asia/Shanghai";
        private String userAgent;
        private boolean blockResourceEnabled = false;
        private boolean humanInputEnabled = true;
        private int humanActionMinDelayMillis = 180;
        private int humanActionMaxDelayMillis = 650;
        private int mouseMoveMinSteps = 24;
        private int mouseMoveMaxSteps = 56;
        private int typingMinDelayMillis = 90;
        private int typingMaxDelayMillis = 260;

        public IdGenerator getPortGenerator() {
            if (portGenerator == null) {
                Linq<Integer> q = Linq.from(Strings.split(remotingPortRange, "-", 2)).select(p -> Integer.valueOf(p));
                portGenerator = new IdGenerator(q.first(), q.last());
            }
            return portGenerator;
        }
    }

    @Data
    public static class CustomTaskConfig {
        private boolean remotingEnabled = true;
        private int remotingListenPort = 1211;
        private int queueMaxConcurrency = 1;
        private int queueTimeoutSeconds = 600;
        private boolean debugEnabled = true;
        private int maxTaskMinutes = 4;
        private ChromeProfileConfig chrome = new ChromeProfileConfig();
        private JdUnionConfig jdUnion = new JdUnionConfig();
    }

    private BrowserPoolConfig browser = new BrowserPoolConfig();
    private CustomTaskConfig custom = new CustomTaskConfig();

    private int fiddlerListenPort;
    private String cleanTaskTime = "03:00:00";
    private String baseDir = "";
    @Value("${server.httpPort}")
    private int httpPort;
    @Value("${server.port}")
    private int httpsPort;

    @Component
    @ConfigurationPropertiesBinding
    public static class RectangleConverter implements Converter<String, BrowserWindowRect> {
        @Override
        public BrowserWindowRect convert(String windowRectangle) {
            if (Strings.isEmpty(windowRectangle)) {
                return null;
            }
            if ("MAX".equalsIgnoreCase(windowRectangle.trim()) || "MAXIMIZED".equalsIgnoreCase(windowRectangle.trim())) {
                return new BrowserWindowRect(0, 0, -1, -1);
            }
            List<Integer> list = Linq.from(windowRectangle.split(",")).select(p -> Integer.valueOf(p)).toList();
            return new BrowserWindowRect(list.get(0), list.get(1), list.get(2), list.get(3));
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
