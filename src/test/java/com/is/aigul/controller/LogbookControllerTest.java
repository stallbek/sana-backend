package com.is.aigul.controller;

import com.is.aigul.service.AuthService;
import com.is.aigul.service.LogbookService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LogbookControllerTest {

    @Test
    void pagePassesAuthenticatedUserAndCombinedFilters() {
        LogbookService logbookService = mock(LogbookService.class);
        AuthService authService = mock(AuthService.class);
        LogbookController controller = new LogbookController(logbookService, authService);
        LocalDateTime start = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 7, 31, 23, 59);
        when(authService.getUserId("token")).thenReturn(7L);

        controller.search(2, 250, "atlas", "sell", start, end, "token");

        verify(logbookService).search(7L, 2, 250, "atlas", "sell", start, end);
    }

    @Test
    void pageRejectsUnknownTokenAndInvalidRange() {
        LogbookService logbookService = mock(LogbookService.class);
        AuthService authService = mock(AuthService.class);
        LogbookController controller = new LogbookController(logbookService, authService);
        when(authService.getUserId("bad")).thenReturn(null);

        assertThatThrownBy(() -> controller.search(0, 20, null, null, null, null, "bad"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
        verifyNoInteractions(logbookService);

        when(authService.getUserId("token")).thenReturn(7L);
        assertThatThrownBy(() -> controller.search(
                0,
                20,
                null,
                null,
                LocalDateTime.of(2026, 8, 1, 0, 0),
                LocalDateTime.of(2026, 7, 1, 0, 0),
                "token"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }
}
