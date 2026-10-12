package com.is.aigul.controller;

import com.is.aigul.dto.LogbookDTO;
import com.is.aigul.dto.LogbookPageResponse;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.LogbookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogbookController {

    private final LogbookService logbookService;
    private final AuthService authService;

    @GetMapping("/page")
    public LogbookPageResponse search(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestHeader(value = "token", required = false) String token
    ) {
        Long userId = requireUserId(token);
        if (start != null && end != null && start.isAfter(end)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "start must be before or equal to end"
            );
        }
        return logbookService.search(userId, page, size, q, actionType, start, end);
    }

    @GetMapping
    public List<LogbookDTO> getAllLogs(@RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return logbookService.getAll(userId);
    }

    @GetMapping("/type/{actionType}")
    public List<LogbookDTO> getByActionType(@PathVariable String actionType, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return logbookService.getByActionType(actionType, userId);
    }

    @GetMapping("/product/{productName}")
    public List<LogbookDTO> getByProduct(@PathVariable String productName, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return logbookService.getByProduct(productName, userId);
    }

    @GetMapping("/location/{locationName}")
    public List<LogbookDTO> getByLocation(@PathVariable String locationName, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return logbookService.getByLocation(locationName, userId);
    }

    @GetMapping("/range")
    public List<LogbookDTO> getByDateRange(
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestHeader("token") String token
    ) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return logbookService.getByDateRange(start, end, userId);
    }

    private Long requireUserId(String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return userId;
    }
}
