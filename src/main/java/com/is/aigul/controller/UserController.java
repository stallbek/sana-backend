package com.is.aigul.controller;

import com.is.aigul.service.AuthService;
import com.is.aigul.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    @PostMapping("/default-location")
    public ResponseEntity<Void> setDefaultLocation(
            @RequestParam String locationName,
            @RequestHeader("token") String token
    ) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        }

        userService.setDefaultLocation(userId, locationName);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/default-location")
    public ResponseEntity<String> getDefaultLocation(
            @RequestHeader("token") String token
    ) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        }

        return ResponseEntity.ok(
                userService.getDefaultLocation(userId)
        );
    }

    @PostMapping("/name")
    public ResponseEntity<Void> setUserName(
            @RequestParam String username,
            @RequestHeader("token") String token
    ) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        }

        userService.setUsername(userId, username);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/name")
    public ResponseEntity<String> getUserName(
            @RequestHeader("token") String token
    ) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        }

        return ResponseEntity.ok(
                userService.getUsername(userId)
        );
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);

        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        return ResponseEntity.ok().build();
    }

}
