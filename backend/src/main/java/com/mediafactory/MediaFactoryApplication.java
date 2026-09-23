package com.mediafactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MediaFactoryApplication {
    public static void main(String[] args) { SpringApplication.run(MediaFactoryApplication.class, args); }
}
