package com.is.aigul.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiLimitsConfiguration {

    @Bean
    Clock aiLimitsClock() {
        return Clock.systemUTC();
    }
}
