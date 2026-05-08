package org.rx.crawler.task.common;

public interface CustomCrawlTask<TRequest, TResult> {
    String taskType();

    TResult execute(TRequest request);
}
