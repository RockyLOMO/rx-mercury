package org.rx.crawler.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.BrowserAsyncTopic;
import org.rx.crawler.BrowserType;
import org.rx.crawler.config.AppConfig;
import org.rx.core.Arrays;
import org.rx.core.Linq;
import org.rx.crawler.util.ProcessUtil;
import org.rx.net.rpc.Remoting;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

import static org.rx.core.Extends.quietly;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserService {
    private final AppConfig config;
    private final BrowserAsyncTopic asyncTopic;
    @Getter
    private BrowserPool pool;

    @PostConstruct
    public void init() {
        purgeProcesses();

        System.setProperty("webdriver.chrome.driver", config.getChrome().getDriver());
        System.setProperty("webdriver.ie.driver", config.getIe().getDriver());
        quietly(() -> Remoting.listen(pool = new BrowserPool(config, asyncTopic), config.getPool().getListenPort(), false));
    }

    private void purgeProcesses() {
        Linq<String> pNames = Linq.from(BrowserType.values()).selectMany(p -> Arrays.toList(p.getDriverName(), p.getProcessName()));
        for (ProcessHandle process : ProcessUtil.getProcesses(pNames.toArray())) {
            log.debug("Kill {}", ProcessUtil.dump(process));
            process.destroyForcibly();
        }
    }
}
