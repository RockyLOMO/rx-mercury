package org.rx.crawler.task.common;

import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoginKeepAliveServiceTest {
    @Test
    public void jdHealthShouldDetectLoginUrl() {
        LoginKeepAliveService service = new LoginKeepAliveService(new AppConfig(), null, null);

        assertTrue(service.isLoginUnhealthy("jd", "https://union.jd.com/index?returnUrl=https%3A%2F%2Funion.jd.com%2Foverview", ""));
        assertTrue(service.isLoginUnhealthy("jd", "https://passport.jd.com/new/login.aspx", ""));
        assertFalse(service.isLoginUnhealthy("jd", "https://union.jd.com/overview", "京东联盟 我要推广 订单"));
    }

    @Test
    public void tbHealthShouldDetectLoginUrl() {
        LoginKeepAliveService service = new LoginKeepAliveService(new AppConfig(), null, null);

        assertTrue(service.isLoginUnhealthy("tb", "https://pub.alimama.com/portal/v2/home/plus/index.htm/_____tmd_____/page/login_jump", ""));
        assertTrue(service.isLoginUnhealthy("tb", "https://login.taobao.com/havanaone/login/login.htm", ""));
        assertFalse(service.isLoginUnhealthy("tb", "https://pub.alimama.com/portal/v2/home/plus/index.htm", "阿里妈妈 淘宝联盟 选品广场"));
    }

    @Test
    public void timeRangeShouldDetermineIfKeepAliveRuns() {
        LoginKeepAliveService service = new LoginKeepAliveService(new AppConfig(), null, null);

        // Case 1: Neither configured -> Should return true
        AppConfig.LoginKeepAliveConfig config1 = new AppConfig.LoginKeepAliveConfig();
        config1.setStartTime(null);
        config1.setEndTime(null);
        assertTrue(service.isInKeepAliveTimeRange(config1));

        // Case 2: Only startTime configured -> Should return true
        AppConfig.LoginKeepAliveConfig config2 = new AppConfig.LoginKeepAliveConfig();
        config2.setStartTime("08:00");
        config2.setEndTime(null);
        assertTrue(service.isInKeepAliveTimeRange(config2));

        // Case 3: Both configured and now is within range
        AppConfig.LoginKeepAliveConfig config3 = new AppConfig.LoginKeepAliveConfig();
        java.time.LocalTime now = java.time.LocalTime.now();
        java.time.LocalTime start = now.minusHours(1);
        java.time.LocalTime end = now.plusHours(1);
        config3.setStartTime(start.toString().substring(0, 5));
        config3.setEndTime(end.toString().substring(0, 5));
        assertTrue(service.isInKeepAliveTimeRange(config3));

        // Case 4: Both configured and now is outside range
        AppConfig.LoginKeepAliveConfig config4 = new AppConfig.LoginKeepAliveConfig();
        java.time.LocalTime start2 = now.plusHours(1);
        java.time.LocalTime end2 = now.plusHours(2);
        config4.setStartTime(start2.toString().substring(0, 5));
        config4.setEndTime(end2.toString().substring(0, 5));
        
        // Wait, if now is e.g. 23:30, now.plusHours(1) will cross midnight and be 00:30, which would wrap.
        // To prevent test flakiness due to midnight wrapping during current time:
        if (now.getHour() < 22) {
            assertFalse(service.isInKeepAliveTimeRange(config4));
        }
    }
}
