package com.is.aigul.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class WalletPassCorsConfiguration {
    @Bean
    public FilterRegistrationBean<CorsFilter> apiCorsFilter(
            @Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toList();
        for (String origin : origins) {
            if (origin.contains("*") || !(origin.startsWith("https://") || origin.startsWith("http://localhost:"))) {
                throw new IllegalStateException("CORS origins must be explicit HTTPS origins or localhost development origins");
            }
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, "token"));
        configuration.setExposedHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION, "X-Serial-Number",
                "X-Google-Save-Url", "X-Pass-Url", HttpHeaders.RETRY_AFTER));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
