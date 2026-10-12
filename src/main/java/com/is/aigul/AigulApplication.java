package com.is.aigul;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AigulApplication {

    public static void main(String[] args) {
        SpringApplication.run(AigulApplication.class, args);
    }

}
