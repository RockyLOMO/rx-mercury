package org.rx.crawler;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.rx.crawler.config.AppConfig;
import org.rx.exception.ExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;

import javax.annotation.Resource;

@SpringBootApplication(scanBasePackages = "org.rx")
public class Application {
    public static void main(String[] args) {
        ExceptionHandler.INSTANCE.enableTrace();
        SpringApplication.run(Application.class, args);
    }

    @Resource
    private AppConfig appConfig;

    @Bean
    public TomcatServletWebServerFactory servletContainer() {
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory() {
            @Override
            protected void postProcessContext(Context context) {
                SecurityConstraint securityConstraint = new SecurityConstraint();
                securityConstraint.setUserConstraint("CONFIDENTIAL");
                SecurityCollection collection = new SecurityCollection();
                collection.addPattern("/*");
                securityConstraint.addCollection(collection);
                context.addConstraint(securityConstraint);
            }
        };
        tomcat.addAdditionalTomcatConnectors(redirectConnector());
        return tomcat;
    }

    private Connector redirectConnector() {
        Connector connector = new Connector();
        connector.setScheme("http");
        connector.setPort(appConfig.getHttpPort());
        connector.setSecure(false);
        connector.setRedirectPort(appConfig.getHttpsPort());
        return connector;
    }
}
