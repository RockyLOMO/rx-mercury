package com.xiaoqing.service;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.ShellCommander;
import org.rx.core.Tasks;
import org.rx.net.PingClient;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.Extends.sleep;

@Slf4j
@Service
public class WinWifiKeeper {
    final AtomicInteger counter = new AtomicInteger();
    String testHost = "cloud.f-li.cn";
    int maxTestFail = 4;
    String wifiName = "CowellTech-Staff";

    @PostConstruct
    public void init() {
        PingClient client = new PingClient();
        Tasks.schedulePeriod(() -> {
            boolean ok = client.isReachable(testHost);
            log.info("WWK test reachable {} -> {} & {}", testHost, ok, counter);
            if (ok) {
                counter.set(0);
                return;
            }
            if (counter.incrementAndGet() > maxTestFail) {
                reset();
            }
        }, 10 * 1000);
    }

    void reset() {
        log.info("WWK reset {} step1", wifiName);
        ShellCommander cmd = new ShellCommander("netsh wlan disconnect");
        cmd.start().waitFor();
        sleep(1000);
        log.info("WWK reset {} step2", wifiName);
        cmd = new ShellCommander(String.format("netsh wlan connect name=\"%s\"", wifiName));
        cmd.start();
        counter.set(0);
    }
}
