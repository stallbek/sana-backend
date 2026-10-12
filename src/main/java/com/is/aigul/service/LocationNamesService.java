package com.is.aigul.service;

import com.is.aigul.model.AppUser;
import com.is.aigul.model.LocationNames;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.ProductLocationRepository;
import com.is.aigul.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LocationNamesService {

    private final LocationNamesRepository locationRepo;
    private final ProductLocationRepository productLocationRepo;
    private final UserRepository userRepository;

    // CREATE
    @Transactional
    public LocationNames createLocation(String name, Long userId) {
        String normalizedName = requireLocationName(name);
        locationRepo.findByLocationNameIgnoreCaseAndUserId(normalizedName, userId)
                .ifPresent(a -> {
                    throw new IllegalArgumentException("Location already exists: " + normalizedName);
                });

        LocationNames loc = new LocationNames();
        loc.setLocationName(normalizedName);
        loc.setUserId(userId);
        LocationNames savedLocation = locationRepo.save(loc);

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (user.getDefaultLocation() == null || user.getDefaultLocation().isBlank()) {
            user.setDefaultLocation(savedLocation.getLocationName());
        }
        return savedLocation;
    }

    // DELETE
    @Transactional
    public String deleteLocation(String name, Long userId) {
        String normalizedName = requireLocationName(name);
        LocationNames loc = locationRepo.findByLocationNameIgnoreCaseAndUserId(normalizedName, userId)
                .orElseThrow(() -> new IllegalArgumentException("Location not found: " + normalizedName));

        List<LocationNames> remainingLocations = locationRepo.findByUserId(userId).stream()
                .filter(candidate -> !candidate.getId().equals(loc.getId()))
                .toList();
        if (remainingLocations.isEmpty()) {
            throw new IllegalArgumentException("Cannot delete the only location");
        }

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        String currentDefault = user.getDefaultLocation();
        boolean defaultWillBeInvalid = currentDefault == null
                || currentDefault.isBlank()
                || currentDefault.equalsIgnoreCase(loc.getLocationName())
                || remainingLocations.stream().noneMatch(candidate ->
                        candidate.getLocationName().equalsIgnoreCase(currentDefault));
        if (defaultWillBeInvalid) {
            LocationNames fallback = remainingLocations.stream()
                    .min(Comparator.comparing(LocationNames::getId))
                    .orElseThrow();
            user.setDefaultLocation(fallback.getLocationName());
        }

        locationRepo.delete(loc);
        return "Location deleted: " + loc.getLocationName();
    }

    // LIST
    public List<LocationNames> getAll(Long userId) {
        return locationRepo.findByUserId(userId);
    }

    @Transactional
    public LocationNames updateLocationName(String oldName, String newName, Long userId) {
        String normalizedOldName = requireLocationName(oldName);
        String normalizedNewName = requireLocationName(newName);

        // Check if location with oldName exists
        LocationNames location = locationRepo.findByLocationNameIgnoreCaseAndUserId(normalizedOldName, userId)
                .orElseThrow(() -> new IllegalArgumentException("Location not found: " + normalizedOldName));

        // Check if newName already exists (avoid duplicates)
        locationRepo.findByLocationNameIgnoreCaseAndUserId(normalizedNewName, userId)
                .filter(existing -> !existing.getId().equals(location.getId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Location name already exists: " + normalizedNewName);
                });

        // Product stock stores the location name separately, so keep both records in sync.
        productLocationRepo.renameLocationForUser(normalizedOldName, normalizedNewName, userId);
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (user.getDefaultLocation() != null
                && user.getDefaultLocation().equalsIgnoreCase(normalizedOldName)) {
            user.setDefaultLocation(normalizedNewName);
        }
        location.setLocationName(normalizedNewName);
        return locationRepo.save(location);
    }

    private String requireLocationName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Location name is required");
        }
        return name.trim();
    }
}
