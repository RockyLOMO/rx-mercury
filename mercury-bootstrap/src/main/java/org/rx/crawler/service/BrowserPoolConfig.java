package org.rx.crawler.service;

import lombok.Data;
import org.openqa.selenium.Rectangle;
import org.rx.core.Linq;
import org.rx.core.Strings;
import org.rx.util.IdGenerator;

@Data
public class BrowserPoolConfig {
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
    private int pageLoadTimeoutSeconds;
    private int findElementTimeoutSeconds;
    private String diskDataPath;
    private String downloadPath;
    private Rectangle windowRectangle;
    private String cookieContainerType;
    private String configureScriptExecutorType;

    public IdGenerator getPortGenerator() {
        if (portGenerator == null) {
            Linq<Integer> q = Linq.from(Strings.split(remotingPortRange, "-", 2)).select(p -> Integer.valueOf(p));
            portGenerator = new IdGenerator(q.first(), q.last());
        }
        return portGenerator;
    }
}
