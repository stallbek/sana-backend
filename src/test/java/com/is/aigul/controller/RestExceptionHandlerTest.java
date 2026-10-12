package com.is.aigul.controller;

import com.is.aigul.exception.AiRateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RestExceptionHandlerTest {

    @Test
    void rateLimitResponseHasStableBodyAndRetryHeader() throws Exception {
        standaloneSetup(new RateLimitedController())
                .setControllerAdvice(new RestExceptionHandler())
                .build()
                .perform(get("/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(jsonPath("$.code").value("AI_RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many AI requests. Please wait before trying again."))
                .andExpect(jsonPath("$.retryAfterSeconds").value(12))
                .andExpect(jsonPath("$.limitType").value("BURST"));
    }

    @RestController
    private static class RateLimitedController {
        @GetMapping("/limited")
        void limited() {
            throw new AiRateLimitException(12, AiRateLimitException.LimitType.BURST);
        }
    }
}
