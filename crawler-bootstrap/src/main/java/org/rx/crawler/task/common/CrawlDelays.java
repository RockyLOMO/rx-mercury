package org.rx.crawler.task.common;

import org.rx.core.Extends;

import java.util.concurrent.ThreadLocalRandom;

public final class CrawlDelays {
    private CrawlDelays() {
    }

    public static int nextDelayMillis(int baseMillis, int randomMillis) {
        int base = Math.max(0, baseMillis);
        int random = Math.max(0, randomMillis);
        if (random == 0) {
            return base;
        }
        return base + ThreadLocalRandom.current().nextInt(random + 1);
    }

    public static void sleep(int baseMillis, int randomMillis) {
        Extends.sleep(nextDelayMillis(baseMillis, randomMillis));
    }
}
