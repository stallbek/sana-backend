package com.is.aigul.controller;

import com.is.aigul.dto.SalesSeriesPeriod;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.BusinessAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalyticsControllerTest {

    @Test
    void endpointsAreUserScopedAndTyped() {
        BusinessAnalyticsService service = mock(BusinessAnalyticsService.class);
        AuthService authService = mock(AuthService.class);
        AnalyticsController controller = new AnalyticsController(service, authService);
        LocalDateTime start = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 7, 31, 23, 59);
        when(authService.getUserId("token")).thenReturn(7L);

        controller.overview(start, end, "token");
        controller.salesSeries(SalesSeriesPeriod.WEEKLY, "token");

        verify(service).overview(7L, start, end);
        verify(service).salesSeries(7L, SalesSeriesPeriod.WEEKLY);
    }

    @Test
    void endpointsRejectUnknownTokenBeforeQuerying() {
        BusinessAnalyticsService service = mock(BusinessAnalyticsService.class);
        AuthService authService = mock(AuthService.class);
        AnalyticsController controller = new AnalyticsController(service, authService);
        when(authService.getUserId("bad")).thenReturn(null);

        assertThatThrownBy(() -> controller.overview(null, null, "bad"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
        verifyNoInteractions(service);
    }
}
