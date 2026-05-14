package org.rx.crawler.task.common;

public interface LoginNotificationService {
    LoginNotificationService NOOP = context -> {
    };

    void notifyLoginRequired(LoginNotificationContext context);
}
