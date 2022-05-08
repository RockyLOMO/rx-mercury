package org.rx.crawler.config;

import com.alibaba.fastjson.TypeReference;
import com.ctrip.framework.apollo.spring.annotation.ApolloJsonValue;
import lombok.Data;
import org.openqa.selenium.Rectangle;
import org.rx.bean.IdGenerator;
import org.rx.crawler.dto.CrawlPageConfig;
import org.rx.crawler.service.ConfigureScriptExecutor;
import org.rx.crawler.service.CookieContainer;
import org.rx.core.NQuery;
import org.rx.core.Reflects;
import org.rx.core.Strings;
import org.rx.crawler.Browser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.NotEmpty;
import java.io.File;
import java.util.List;
import java.util.Objects;

import static org.rx.core.App.fromJson;

@Data
@Component("appConfig")
@ConfigurationProperties(prefix = "app")
@Validated
@Order(1)
@RefreshScope
public class AppConfig {
    @Data
    public static class ChromeConfig {
        private String driver;
        private int poolSize;
//        private String diskDataPath;
        private String downloadPath;
        private boolean isBackground;
    }

    @Data
    public static class IEConfig {
        private String driver;
        private int poolSize;
    }

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

    @Data
    public static class PoolConfig {
        private int listenPort;
        private long maintenancePeriod;
        private int maxActiveMinutes;
        private long dumpPeriod;
        private float asyncThreshold;
        private int takeTimeoutSeconds = 6;
        private String remotingPortRange = "1220-1320";
        private IdGenerator portGenerator;

        public IdGenerator getPortGenerator() {
            if (portGenerator == null) {
                NQuery<Integer> q = NQuery.of(Strings.split(remotingPortRange, "-", 2)).select(p -> Integer.valueOf(p));
                portGenerator = new IdGenerator(q.first(), q.last());
            }
            return portGenerator;
        }
    }

    public static final String CACHE_PDD_GOODS_MAP = "RubbishPdd";

    private ProxyConfig proxy;
    private PoolConfig pool;
    private String windowRectangle = "600,500,800,600";
    private boolean windowAutoBlank = true;
    private int waitMillis = 500;
    private int pageLoadTimeoutSeconds = 30;
    private int findElementTimeoutSeconds = 6;

    String[] test;
    private ChromeConfig chrome;
    private IEConfig ie;
    //    @NotEmpty
    private CookieContainer cookieContainer;
    @NotEmpty
    private String configureScriptExecutorType;
    private int fiddlerListenPort;
    @Value("${server.httpPort}")
    private int httpPort;
    @Value("${server.port}")
    private int httpsPort;

    public Rectangle getWindowRectangleBean() {
        if (Strings.isEmpty(windowRectangle)) {
            return null;
        }
        List<Integer> list = NQuery.of(windowRectangle.split(",")).select(p -> Integer.valueOf(p)).toList();
        //width height 反了
        return new Rectangle(list.get(0), list.get(1), list.get(3), list.get(2));
    }

    public synchronized ConfigureScriptExecutor createScriptExecutor(Browser owner) {
        Objects.requireNonNull(configureScriptExecutorType);

        return Reflects.newInstance(Reflects.loadClass(configureScriptExecutorType, true), owner);
    }

    public String getFiddlerPath() {
        return new File("./fiddler").getAbsolutePath();
    }

    void onchange() {
        pool.portGenerator = null;
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

    @Component
    @ConfigurationPropertiesBinding
    public static class CookieContainerConverter implements Converter<String, CookieContainer> {
        @Override
        public CookieContainer convert(String s) {
            return Reflects.newInstance(Reflects.loadClass(s, true));
        }
    }
}
