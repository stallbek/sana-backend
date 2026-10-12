package com.is.aigul.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "sana-backend",
                "sokuluk", "UP"
        );
    }
}
