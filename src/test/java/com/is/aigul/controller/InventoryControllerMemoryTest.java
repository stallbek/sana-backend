package com.is.aigul.controller;

import com.is.aigul.dto.ActionResponse;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.service.AuthService;
import com.is.aigul.service.CommandPreprocessorService;
import com.is.aigul.service.InventoryService;
import com.is.aigul.service.ShortTermMemoryService;
import com.is.aigul.service.AiUsageLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class InventoryControllerMemoryTest {

    @Test
    void executeCommandClearsPendingMemoryAfterSuccessOnly() {
        InventoryService inventoryService = mock(InventoryService.class);
        AuthService authService = mock(AuthService.class);
        ShortTermMemoryService memoryService = mock(ShortTermMemoryService.class);
        InventoryController controller = new InventoryController();
        CommandDTO command = new CommandDTO();
        command.setOperation("ADD");
        com.is.aigul.dto.SellItemDTO item = new com.is.aigul.dto.SellItemDTO();
        item.setProductName("Atlas"); item.setLocation("Shop"); item.setQuantity(1);
        command.setItems(java.util.List.of(item));

        ReflectionTestUtils.setField(controller, "inventoryService", inventoryService);
        ReflectionTestUtils.setField(controller, "preprocessService", mock(CommandPreprocessorService.class));
        ReflectionTestUtils.setField(controller, "authService", authService);
        ReflectionTestUtils.setField(controller, "shortTermMemoryService", memoryService);
        ReflectionTestUtils.setField(controller, "aiUsageLimitService", mock(AiUsageLimitService.class));

        when(authService.getUserId("token")).thenReturn(7L);
        when(inventoryService.executeCommand(command, 7L))
                .thenReturn(new ActionResponse("ACTION_COMPLETED", "ok", null))
                .thenReturn(new ActionResponse("ERROR", "failed", null));

        controller.executeCommand(command, "token");
        controller.executeCommand(command, "token");

        verify(memoryService, times(1)).clearMemory(7L);
    }
}
