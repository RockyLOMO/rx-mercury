package com.xiaoqing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"org.rx", "com.xiaoqing"})
public class QingApplication {
    public static void main(String[] args) {
        SpringApplication.run(QingApplication.class, args);
    }
}
