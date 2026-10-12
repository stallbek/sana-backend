package com.is.aigul.service;

import com.is.aigul.model.AppUser;
import com.is.aigul.model.LocationNames;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.ProductLocationRepository;
import com.is.aigul.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocationNamesServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private LocationNamesRepository locationRepository;
    @Mock
    private ProductLocationRepository productLocationRepository;
    @Mock
    private UserRepository userRepository;

    private LocationNamesService locationNamesService;

    @BeforeEach
    void setUp() {
        locationNamesService = new LocationNamesService(
                locationRepository,
                productLocationRepository,
                userRepository
        );
    }

    @Test
    void renameKeepsProductStockAssignedToRenamedLocation() {
        LocationNames location = new LocationNames(3L, USER_ID, "Old Warehouse");
        when(locationRepository.findByLocationNameIgnoreCaseAndUserId("Old Warehouse", USER_ID))
                .thenReturn(Optional.of(location));
        when(locationRepository.findByLocationNameIgnoreCaseAndUserId("Main Warehouse", USER_ID))
                .thenReturn(Optional.empty());
        AppUser user = user("Old Warehouse");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(locationRepository.save(location)).thenReturn(location);

        LocationNames renamed = locationNamesService.updateLocationName(
                "Old Warehouse",
                "Main Warehouse",
                USER_ID
        );

        InOrder updates = inOrder(productLocationRepository, locationRepository);
        updates.verify(productLocationRepository)
                .renameLocationForUser("Old Warehouse", "Main Warehouse", USER_ID);
        updates.verify(locationRepository).save(location);
        assertThat(renamed.getLocationName()).isEqualTo("Main Warehouse");
        assertThat(user.getDefaultLocation()).isEqualTo("Main Warehouse");
    }

    @Test
    void renameDoesNotMoveProductStockWhenTargetNameAlreadyExists() {
        LocationNames oldLocation = new LocationNames(3L, USER_ID, "Old Warehouse");
        LocationNames existingTarget = new LocationNames(4L, USER_ID, "Main Warehouse");
        when(locationRepository.findByLocationNameIgnoreCaseAndUserId("Old Warehouse", USER_ID))
                .thenReturn(Optional.of(oldLocation));
        when(locationRepository.findByLocationNameIgnoreCaseAndUserId("Main Warehouse", USER_ID))
                .thenReturn(Optional.of(existingTarget));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> locationNamesService.updateLocationName(
                        "Old Warehouse",
                        "Main Warehouse",
                        USER_ID
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Location name already exists");

        verifyNoInteractions(productLocationRepository);
    }

    @Test
    void firstLocationAutomaticallyBecomesDefault() {
        AppUser user = user(null);
        LocationNames saved = new LocationNames(3L, USER_ID, "Main Warehouse");
        when(locationRepository.findByLocationNameIgnoreCaseAndUserId("Main Warehouse", USER_ID))
                .thenReturn(Optional.empty());
        when(locationRepository.save(org.mockito.ArgumentMatchers.any(LocationNames.class)))
                .thenReturn(saved);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        locationNamesService.createLocation("  Main Warehouse  ", USER_ID);

        assertThat(user.getDefaultLocation()).isEqualTo("Main Warehouse");
    }

    @Test
    void onlyLocationCannotBeDeleted() {
        LocationNames onlyLocation = new LocationNames(3L, USER_ID, "Main Warehouse");
        when(locationRepository.findByLocationNameIgnoreCaseAndUserId("Main Warehouse", USER_ID))
                .thenReturn(Optional.of(onlyLocation));
        when(locationRepository.findByUserId(USER_ID)).thenReturn(List.of(onlyLocation));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        locationNamesService.deleteLocation("Main Warehouse", USER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot delete the only location");

        verify(locationRepository, never()).delete(onlyLocation);
    }

    @Test
    void deletingDefaultSelectsOldestRemainingLocation() {
        LocationNames current = new LocationNames(3L, USER_ID, "Current");
        LocationNames oldestRemaining = new LocationNames(4L, USER_ID, "Warehouse");
        LocationNames newestRemaining = new LocationNames(5L, USER_ID, "Store");
        AppUser user = user("Current");
        when(locationRepository.findByLocationNameIgnoreCaseAndUserId("Current", USER_ID))
                .thenReturn(Optional.of(current));
        when(locationRepository.findByUserId(USER_ID))
                .thenReturn(List.of(current, newestRemaining, oldestRemaining));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        locationNamesService.deleteLocation("Current", USER_ID);

        assertThat(user.getDefaultLocation()).isEqualTo("Warehouse");
        verify(locationRepository).delete(current);
    }

    private AppUser user(String defaultLocation) {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setDefaultLocation(defaultLocation);
        return user;
    }
}
