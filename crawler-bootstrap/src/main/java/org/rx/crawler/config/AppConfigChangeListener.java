package org.rx.crawler.config;

import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import com.ctrip.framework.apollo.spring.annotation.ApolloConfigChangeListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.service.RWebConfig;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import static org.rx.core.Sys.toJsonString;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppConfigChangeListener {
    final RefreshScope refreshScope;
    final AppConfig appConfig;
    final org.springframework.context.ApplicationContext applicationContext;

    @PostConstruct
    public void init() {
        RWebConfig.enableTrace(null);
    }

    @ApolloConfigChangeListener
    public void onChange(ConfigChangeEvent changeEvent) {
        log.info("before refresh {}", toJsonString(appConfig));
        applicationContext.publishEvent(new EnvironmentChangeEvent(changeEvent.changedKeys()));
        refreshScope.refreshAll();
        log.info("after refresh {}", toJsonString(appConfig));
    }
}
