package com.is.aigul.controller;

import com.is.aigul.dto.ActionResponse;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private CommandPreprocessorService preprocessService;
    @Autowired
    private AuthService authService;
    @Autowired
    private ShortTermMemoryService shortTermMemoryService;
    @Autowired
    private AiUsageLimitService aiUsageLimitService;

    @PostMapping("/command")
    public ActionResponse executeCommand(@RequestBody CommandDTO cmd, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        CommandValidator.validateMutation(cmd);
        return executeAndClearMemory(cmd, userId);
    }

    @PostMapping("/undo")
    public ActionResponse undoCommand(@RequestBody CommandDTO cmd, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        return inventoryService.undoCommand(cmd, userId);
    }

    private ActionResponse executeAndClearMemory(CommandDTO cmd, Long userId) {
        ActionResponse response = inventoryService.executeCommand(cmd, userId);
        if (response != null && !"ERROR".equalsIgnoreCase(response.getType())) {
            shortTermMemoryService.clearMemory(userId);
        }
        return response;
    }
}
