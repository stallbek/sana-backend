package com.is.aigul.controller;
import com.is.aigul.model.LocationNames;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.LocationNamesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/api/locations")
@RequiredArgsConstructor
public class LocationNamesController {

    private final LocationNamesService locationService;
    private final AuthService authService;

    @PostMapping
    public LocationNames create(@RequestParam String name, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return locationService.createLocation(name, userId);
    }

    @DeleteMapping
    public String delete(@RequestParam String name, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return locationService.deleteLocation(name, userId);
    }

    @GetMapping
    public List<LocationNames> list(@RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return locationService.getAll(userId);
    }

    @PutMapping
    public LocationNames update(
            @RequestParam String oldName,
            @RequestParam String newName,
            @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return locationService.updateLocationName(oldName, newName, userId);
    }
}
