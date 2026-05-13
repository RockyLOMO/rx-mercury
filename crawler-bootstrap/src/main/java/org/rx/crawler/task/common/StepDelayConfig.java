package org.rx.crawler.task.common;

public interface StepDelayConfig {
    int getStepDelayMillis();

    int getStepDelayRandomMillis();

    default int nextStepDelayMillis() {
        return CrawlDelays.nextDelayMillis(getStepDelayMillis(), getStepDelayRandomMillis());
    }
}
