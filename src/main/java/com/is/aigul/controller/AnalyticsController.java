package com.is.aigul.controller;

import com.is.aigul.dto.AnalyticsOverviewResponse;
import com.is.aigul.dto.SalesSeriesPeriod;
import com.is.aigul.dto.SalesSeriesResponse;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.BusinessAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final BusinessAnalyticsService businessAnalyticsService;
    private final AuthService authService;

    @GetMapping("/overview")
    public AnalyticsOverviewResponse overview(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestHeader(value = "token", required = false) String token
    ) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "start must be before or equal to end"
            );
        }
        return businessAnalyticsService.overview(requireUserId(token), start, end);
    }

    @GetMapping("/sales-series")
    public SalesSeriesResponse salesSeries(
            @RequestParam SalesSeriesPeriod period,
            @RequestHeader(value = "token", required = false) String token
    ) {
        return businessAnalyticsService.salesSeries(requireUserId(token), period);
    }

    private Long requireUserId(String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return userId;
    }
}
