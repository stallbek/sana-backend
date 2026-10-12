package com.is.aigul.controller;

import com.is.aigul.dto.CardEditRequest;
import com.is.aigul.dto.CommandDTO;
import com.is.aigul.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/command")
@RequiredArgsConstructor
public class CommandController {

    private final CommandPreprocessorService commandPreprocessorService;
    private final FullService fullService;
    private final CardEditService cardEditService;
    private final AuthService authService;
    private final ShortTermMemoryService shortTermMemoryService;
    private final AiUsageLimitService aiUsageLimitService;


    @PostMapping("/process")
    public CommandDTO processCommand(@RequestBody CommandDTO cmd, @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        CommandDTO processed = commandPreprocessorService.preprocess(cmd, userId);
        return processed;
    }

    @PostMapping("/text")
    public java.util.List<CommandDTO> processFullText(@RequestBody String text, @RequestHeader("token") String token){
        Long userId = authService.getUserId(token);
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized");
        aiUsageLimitService.validateText(text);
        try (AiUsageLimitService.Permit ignored = aiUsageLimitService.reserve(userId, AiOperation.TEXT_COMMAND)) {
            java.util.List<CommandDTO> processed = fullService.processTextFully(text, userId);
            return processed;
        }
    }

    @PostMapping("/edit/text")
    public ResponseEntity<CommandDTO> editTextCard(@RequestBody CardEditRequest request,
            @RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        try {
            if (request == null || request.getCard() == null
                    || request.getCard().getOperation() == null || request.getCard().getOperation().isBlank()) {
                return ResponseEntity.badRequest().build();
            }
            aiUsageLimitService.validateText(request.getText());
            try (AiUsageLimitService.Permit ignored = aiUsageLimitService.reserve(userId, AiOperation.TEXT_CARD_EDIT)) {
                return ResponseEntity.ok(cardEditService.editTextCard(request.getText(), request.getCard(), userId));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/memory/clear")
    public ResponseEntity<Void> clearPendingMemory(@RequestHeader("token") String token) {
        Long userId = authService.getUserId(token);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        shortTermMemoryService.clearMemory(userId);
        return ResponseEntity.ok().build();
    }
}
