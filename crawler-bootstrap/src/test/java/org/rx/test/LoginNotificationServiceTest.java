package org.rx.test;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.task.common.LoginNotificationContext;
import org.rx.crawler.task.common.MailLoginNotificationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
public class LoginNotificationServiceTest {
    @Test
    public void notifyLoginRequiredShouldSendMailAndThrottleDuplicate() {
        AppConfig appConfig = new AppConfig();
        appConfig.getCustom().getLoginNotification().setEnabled(true);
        appConfig.getCustom().getLoginNotification().setMinIntervalSeconds(300);
        appConfig.getCustom().getLoginNotification().getMail().setFrom("robot@example.com");
        appConfig.getCustom().getLoginNotification().getMail().getTo().add("ops@example.com");

        JavaMailSender mailSender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);

        MailLoginNotificationService service = new MailLoginNotificationService(appConfig, provider);
        LoginNotificationContext context = new LoginNotificationContext();
        context.setTaskType("getPromotionUrl");
        context.setProfileName("common");
        context.setInitialUrl("https://union.jd.com/overview");
        context.setCurrentUrl("https://union.jd.com/index?returnUrl=abc");
        context.setMessage("Login required");
        context.setLoginWaitSeconds(180);
        context.setKeepBrowserOpenSeconds(180);

        service.notifyLoginRequired(context);
        service.notifyLoginRequired(context);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertTrue(message.getSubject().contains("getPromotionUrl"));
        assertTrue(message.getText().contains("任务需要人工登录接管"));
        assertTrue(message.getText().contains("common"));
    }

    @Test
    public void notifyLoginRequiredShouldSkipWhenDisabled() {
        AppConfig appConfig = new AppConfig();
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);

        MailLoginNotificationService service = new MailLoginNotificationService(appConfig, provider);
        service.notifyLoginRequired(new LoginNotificationContext());

        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
    }
}
