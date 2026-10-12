package com.is.aigul.service;

import com.is.aigul.model.AppUser;
import com.is.aigul.model.LocationNames;
import com.is.aigul.repository.LocationNamesRepository;
import com.is.aigul.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private LocationNamesRepository locationNamesRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, locationNamesRepository);
    }

    @Test
    void setDefaultRequiresOwnedLocationAndUsesCanonicalName() {
        AppUser user = user(null);
        LocationNames location = new LocationNames(3L, USER_ID, "Main Warehouse");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(locationNamesRepository.findByLocationNameIgnoreCaseAndUserId("main warehouse", USER_ID))
                .thenReturn(Optional.of(location));

        userService.setDefaultLocation(USER_ID, "  main warehouse  ");

        assertThat(user.getDefaultLocation()).isEqualTo("Main Warehouse");
    }

    @Test
    void setDefaultRejectsUnknownLocation() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(null)));
        when(locationNamesRepository.findByLocationNameIgnoreCaseAndUserId("Unknown", USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.setDefaultLocation(USER_ID, "Unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Location not found: Unknown");
    }

    @Test
    void getDefaultRepairsMissingValueWithOldestLocation() {
        AppUser user = user(null);
        LocationNames fallback = new LocationNames(3L, USER_ID, "Main Warehouse");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(locationNamesRepository.findFirstByUserIdOrderByIdAsc(USER_ID))
                .thenReturn(Optional.of(fallback));

        assertThat(userService.getDefaultLocation(USER_ID)).isEqualTo("Main Warehouse");
        assertThat(user.getDefaultLocation()).isEqualTo("Main Warehouse");
    }

    @Test
    void getDefaultFailsClearlyWhenUserHasNoLocations() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(null)));
        when(locationNamesRepository.findFirstByUserIdOrderByIdAsc(USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getDefaultLocation(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User must have at least one location");
    }

    @Test
    void getUsernameUsesNeutralLabelWithoutLegacyUsername() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(null)));

        assertThat(userService.getUsername(USER_ID)).isEqualTo("User");
    }

    private AppUser user(String defaultLocation) {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setDefaultLocation(defaultLocation);
        return user;
    }
}
