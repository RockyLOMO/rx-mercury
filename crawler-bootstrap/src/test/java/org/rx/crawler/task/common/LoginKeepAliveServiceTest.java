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
}
