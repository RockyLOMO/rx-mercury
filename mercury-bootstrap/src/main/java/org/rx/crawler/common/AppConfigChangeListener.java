package org.rx.crawler.common;

import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import com.ctrip.framework.apollo.spring.annotation.ApolloConfigChangeListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.stereotype.Component;

import static org.rx.core.App.toJsonString;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppConfigChangeListener {
    private final RefreshScope refreshScope;
    private final AppConfig appConfig;

    @ApolloConfigChangeListener
    public void onChange(ConfigChangeEvent changeEvent) {
        log.info("before refresh {}", toJsonString(appConfig));
        refreshScope.refresh("appConfig");
        appConfig.onchange();
        log.info("after refresh {}", toJsonString(appConfig));
    }
}
