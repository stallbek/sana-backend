package com.is.aigul.service;

import com.is.aigul.config.WalletWalletProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class WalletWalletProxyServiceSpringContextTest {

    @Test
    void springConstructsProxyServiceWithoutDefaultConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(WalletWalletProperties.class, WalletWalletProxyService.class);
            context.refresh();

            assertThat(context.getBean(WalletWalletProxyService.class)).isNotNull();
        }
    }
}
