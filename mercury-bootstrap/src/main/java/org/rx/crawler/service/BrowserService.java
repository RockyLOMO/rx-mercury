package org.rx.crawler.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Arrays;
import org.rx.core.Linq;
import org.rx.crawler.BrowserAsyncTopic;
import org.rx.crawler.BrowserType;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.util.ProcessUtil;
import org.rx.net.rpc.Remoting;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

import static org.rx.core.Extends.quietly;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserService {
    final AppConfig config;
    final BrowserAsyncTopic asyncTopic;
    @Getter
    private BrowserPool pool;

    @PostConstruct
    public void init() {
        purgeProcesses();

        System.setProperty("webdriver.chrome.driver", config.getChromeDriver());
        System.setProperty("webdriver.gecko.driver", config.getFireFoxDriver());
        System.setProperty("webdriver.ie.driver", config.getIeDriver());
        quietly(() -> Remoting.register(pool = new BrowserPool(config.getBrowser(), asyncTopic), pool.conf.getListenPort(), false));
    }

    private void purgeProcesses() {
        Linq<String> pNames = Linq.from(BrowserType.values()).selectMany(p -> Arrays.toList(p.getDriverName(), p.getProcessName()));
        for (ProcessHandle process : ProcessUtil.getProcesses(pNames.toArray())) {
            log.debug("Kill {}", ProcessUtil.dump(process));
            process.destroyForcibly();
        }
    }
}
