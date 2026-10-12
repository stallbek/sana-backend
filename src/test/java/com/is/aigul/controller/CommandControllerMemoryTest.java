package com.is.aigul.controller;

import com.is.aigul.service.AuthService;
import com.is.aigul.service.CardEditService;
import com.is.aigul.service.CommandPreprocessorService;
import com.is.aigul.service.FullService;
import com.is.aigul.service.ShortTermMemoryService;
import com.is.aigul.service.AiUsageLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CommandControllerMemoryTest {

    @Test
    void clearPendingMemoryClearsAuthenticatedUsersMemory() {
        AuthService authService = mock(AuthService.class);
        ShortTermMemoryService memoryService = mock(ShortTermMemoryService.class);
        CommandController controller = controller(authService, memoryService);

        when(authService.getUserId("token")).thenReturn(7L);

        ResponseEntity<Void> response = controller.clearPendingMemory("token");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(memoryService).clearMemory(7L);
    }

    @Test
    void clearPendingMemoryRejectsUnauthorizedToken() {
        AuthService authService = mock(AuthService.class);
        ShortTermMemoryService memoryService = mock(ShortTermMemoryService.class);
        CommandController controller = controller(authService, memoryService);

        when(authService.getUserId("bad-token")).thenReturn(null);

        ResponseEntity<Void> response = controller.clearPendingMemory("bad-token");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(memoryService);
    }

    private CommandController controller(AuthService authService, ShortTermMemoryService memoryService) {
        return new CommandController(
                mock(CommandPreprocessorService.class),
                mock(FullService.class),
                mock(CardEditService.class),
                authService,
                memoryService,
                mock(AiUsageLimitService.class));
    }
}
