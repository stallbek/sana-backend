package com.is.aigul.service;

import com.is.aigul.model.AppUser;
import com.is.aigul.model.LocationNames;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final LocationNamesRepository locationNamesRepository;

    @Transactional
    public void setDefaultLocation(Long userId, String locationName) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String normalizedName = requireLocationName(locationName);
        LocationNames location = locationNamesRepository
                .findByLocationNameIgnoreCaseAndUserId(normalizedName, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Location not found: " + normalizedName));

        user.setDefaultLocation(location.getLocationName());
    }

    @Transactional
    public String getDefaultLocation(Long userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String currentDefault = user.getDefaultLocation();
        if (currentDefault != null && !currentDefault.isBlank()) {
            Optional<LocationNames> currentLocation = locationNamesRepository
                    .findByLocationNameIgnoreCaseAndUserId(currentDefault.trim(), userId);
            if (currentLocation.isPresent()) {
                String canonicalName = currentLocation.get().getLocationName();
                if (!canonicalName.equals(currentDefault)) {
                    user.setDefaultLocation(canonicalName);
                }
                return canonicalName;
            }
        }

        LocationNames fallback = locationNamesRepository.findFirstByUserIdOrderByIdAsc(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "User must have at least one location"));
        user.setDefaultLocation(fallback.getLocationName());
        return fallback.getLocationName();
    }

    @Transactional
    public void setUsername(Long userId, String username) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setName(username);
    }

    public String getUsername(Long userId) {
        return userRepository.findById(userId)
                .map(user -> user.getUsername() == null || user.getUsername().isBlank()
                        ? "User"
                        : user.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private String requireLocationName(String locationName) {
        if (locationName == null || locationName.isBlank()) {
            throw new IllegalArgumentException("Default location is required");
        }
        return locationName.trim();
    }
}
